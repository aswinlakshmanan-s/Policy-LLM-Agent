package com.northeastern.scraper;

import akka.actor.typed.*;
import akka.actor.typed.javadsl.*;
import akka.actor.typed.javadsl.AskPattern;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class PolicyBotMain {

    public static void main(String[] args) throws Exception {
        // Pre-flight check BEFORE creating the actor system
        Dependencies deps = checkDependencies();

        ActorSystem<RootCommand> system = ActorSystem.create(
                PolicyBotRoot.create(deps.embedder(), deps.qdrant()),
                "PolicyBot"
        );

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("🤖 Policy Bot is ready! Ask questions about Northeastern University policies.");

        while (true) {
            System.out.print("Ask a policy question (or 'exit'): ");
            String q = reader.readLine();
            if (q == null || q.trim().equalsIgnoreCase("exit")) break;

            System.out.println("[MAIN] Processing question: " + q);
            CompletableFuture<String> answerFuture = new CompletableFuture<>();

            system.tell(new RootCommand.UserQuestion(q, answerFuture));

            try {
                String answer = answerFuture.get();
                System.out.println("\n" + "=".repeat(50));
                System.out.println("Answer:");
                System.out.println(answer);
                System.out.println("=".repeat(50) + "\n");
            } catch (Exception e) {
                System.err.println("[MAIN] Error getting answer: " + e.getMessage());
                System.out.println("Please try again in a few moments.\n");
            }
        }

        System.out.println("Goodbye!");
        system.terminate();
    }

    // ===== Protocol for the root guardian =====
    public interface RootCommand extends Serializable {
        record UserQuestion(String query, CompletableFuture<String> replyTo) implements RootCommand {}
        record SearchFinished(String query, List<QdrantClient.PolicyMatch> matches,
                              CompletableFuture<String> replyTo) implements RootCommand {}
        record LlmDone(String text, CompletableFuture<String> replyTo) implements RootCommand {}
    }

    public static class PolicyBotRoot extends AbstractBehavior<RootCommand> {
        private final ActorRef<LoggerActor.LogMsg> logger;
        private final ActorRef<PolicySearchActor.SearchMsg> search;
        private final ActorRef<LLMActor.LLMMsg> llm;
        private final Duration timeout = Duration.ofSeconds(30);

        // Track active requests to prevent duplicate completions
        private final java.util.Set<CompletableFuture<String>> activeRequests = new java.util.HashSet<>();
        private final java.util.Set<String> scheduledTimeouts = new java.util.HashSet<>();

        // Modified to accept dependencies as parameters
        public static Behavior<RootCommand> create(EmbeddingService embedder, QdrantClient qdrant) {
            return Behaviors.setup(ctx -> {
                var logger = ctx.spawn(LoggerActor.create(), "Logger");
                var search = ctx.spawn(PolicySearchActor.create(qdrant, embedder), "PolicySearch");
                var llm    = ctx.spawn(LLMActor.create(), "LLM");

                return new PolicyBotRoot(ctx, logger, search, llm);
            });
        }

        public PolicyBotRoot(ActorContext<RootCommand> ctx,
                             ActorRef<LoggerActor.LogMsg> logger,
                             ActorRef<PolicySearchActor.SearchMsg> search,
                             ActorRef<LLMActor.LLMMsg> llm) {
            super(ctx);
            this.logger = logger;
            this.search = search;
            this.llm = llm;
        }

        @Override
        public Receive<RootCommand> createReceive() {
            return newReceiveBuilder()
                    .onMessage(RootCommand.UserQuestion.class, this::onUserQuestion)
                    .onMessage(RootCommand.SearchFinished.class, this::onSearchFinished)
                    .onMessage(RootCommand.LlmDone.class, this::onLlmDone)
                    .build();
        }

        private Behavior<RootCommand> onUserQuestion(RootCommand.UserQuestion msg) {
            System.out.println("[ROOT] Processing user question: " + msg.query);

            // Track this request
            activeRequests.add(msg.replyTo);

            // ask → search (typed AskPattern)
            CompletionStage<PolicySearchActor.Response> fut =
                    AskPattern.ask(
                            search,
                            (ActorRef<PolicySearchActor.Response> r) -> new PolicySearchActor.Query(msg.query, r),
                            timeout,
                            getContext().getSystem().scheduler()
                    );

            logger.tell(new LoggerActor.Log("User asked: " + msg.query));

            // pipe future back to self as a message
            getContext().pipeToSelf(
                    fut,
                    (ok, ex) -> {
                        System.out.println("[ROOT] Search completed - success: " + (ok != null));
                        if (ex != null) {
                            System.err.println("[ROOT] Search failed: " + ex.getMessage());
                        }
                        return new RootCommand.SearchFinished(
                                msg.query,
                                (ex != null || ok == null) ? java.util.List.of() : ok.results,
                                msg.replyTo
                        );
                    }
            );

            return this;
        }

        private Behavior<RootCommand> onSearchFinished(RootCommand.SearchFinished msg) {
            System.out.println("[ROOT] Search finished with " + msg.matches.size() + " policy matches");

            // If no active request, ignore
            if (!activeRequests.contains(msg.replyTo)) {
                System.out.println("[ROOT] No active request found, ignoring search result");
                return this;
            }

            // adapter for LLM → Root
            ActorRef<LLMActor.LLMResponse> llmReply =
                    getContext().messageAdapter(
                            LLMActor.LLMResponse.class,
                            r -> {
                                System.out.println("[ROOT] LLM response received: " +
                                        r.answer.substring(0, Math.min(100, r.answer.length())) + "...");
                                return new RootCommand.LlmDone(r.answer, msg.replyTo);
                            }
                    );

            System.out.println("[ROOT] Sending request to LLM...");
            llm.tell(new LLMActor.Summarize(msg.query, msg.matches, llmReply));

            // Safety timeout fallback with unique ID to avoid race conditions
            String timeoutId = msg.query + "_" + System.currentTimeMillis();
            scheduledTimeouts.add(timeoutId);

            getContext().scheduleOnce(
                    Duration.ofSeconds(70), // Longer than LLM timeout to avoid race condition
                    getContext().getSelf(),
                    new RootCommand.LlmDone(
                            "⏰ **Request timed out** - The AI service took too long to respond.\n\n" +
                                    "**Here are the relevant policy excerpts I found:**\n\n" +
                                    msg.matches.stream().limit(3)
                                            .map(m -> "📋 **" + m.title + "** (relevance: " +
                                                    String.format("%.1f%%", m.score * 100) + ")\n" +
                                                    "   " + (m.text.length() > 400 ? m.text.substring(0, 400) + "..." : m.text))
                                            .collect(java.util.stream.Collectors.joining("\n\n")) +
                                    "\n\n**Please try your question again in 2-3 minutes.**",
                            msg.replyTo
                    )
            );

            return this;
        }

        private Behavior<RootCommand> onLlmDone(RootCommand.LlmDone msg) {
            System.out.println("[ROOT] LLM done - completing response");

            // Ensure we only complete the future once and it's an active request
            if (activeRequests.contains(msg.replyTo) && !msg.replyTo.isDone()) {
                msg.replyTo.complete(msg.text);
                activeRequests.remove(msg.replyTo);
                System.out.println("[ROOT] Successfully completed request");
            } else if (msg.replyTo.isDone()) {
                System.out.println("[ROOT] Request already completed, ignoring duplicate");
            } else {
                System.out.println("[ROOT] Request not active, ignoring response");
            }
            return this;
        }
    }

    // Dependency record and check method
    private static record Dependencies(EmbeddingService embedder, QdrantClient qdrant) {}

    private static Dependencies checkDependencies() {
        System.out.println("🚀 Starting dependency pre-flight check...");
        try {
            // Check EmbeddingService
            System.out.println("Initializing EmbeddingService...");
            var embedder = new EmbeddingService(null);

            // Optional warmup for faster first query
            try {
                System.out.println("Warming up embedder...");
                embedder.embed("warmup test");
                System.out.println("✅ EmbeddingService warmup successful.");
            } catch (Exception e) {
                System.out.println("⚠️ EmbeddingService warmup failed, but service is initialized: " + e.getMessage());
            }

            // Check QdrantClient
            System.out.println("Connecting to Qdrant...");
            var qdrant = new QdrantClient("http://127.0.0.1:6333");
            long count = qdrant.countPoints();
            System.out.println("✅ QdrantClient is connected. Found " + count + " indexed policies.");

            if (count == 0) {
                System.out.println("⚠️ No policies found in database. Run PolicyIndexer first!");
            }

            // Ensure collection has correct vector size
            try {
                float[] testEmbedding = embedder.embed("test");
                qdrant.ensureCollectionExactly(testEmbedding.length);
                System.out.println("✅ Vector collection verified with size: " + testEmbedding.length);
            } catch (Exception e) {
                System.err.println("⚠️ Could not verify vector collection: " + e.getMessage());
            }

            System.out.println("All systems go! 🤖\n");
            return new Dependencies(embedder, qdrant);
        } catch (Exception e) {
            System.err.println("❌ Dependency check failed. The application cannot start.");
            System.err.println("Cause: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();

            // Provide helpful troubleshooting info
            System.err.println("\n🔧 Troubleshooting tips:");
            System.err.println("1. Ensure Qdrant is running: docker run -p 6333:6333 qdrant/qdrant");
            System.err.println("2. Check if OPENAI_API_KEY environment variable is set");
            System.err.println("3. Verify internet connection for DJL model download");
            System.err.println("4. Run PolicyIndexer first to populate the vector database");

            System.exit(1);
            return null;
        }
    }
}