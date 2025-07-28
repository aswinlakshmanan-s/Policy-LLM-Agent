package com.northeastern.scraper;

import akka.actor.typed.*;
import akka.actor.typed.javadsl.*;
import java.io.Serializable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class PolicyBotMain {
    public static void main(String[] args) throws Exception {
        String openAiKey = System.getenv("COHERE_API_KEY");
        if (openAiKey == null) {
            System.err.println("Set your COHERE_API_KEY as an environment variable!");
            System.exit(1);
        }

        ActorSystem<RootCommand> system = ActorSystem.create(PolicyBotRoot.create(openAiKey), "PolicyBot");

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        while (true) {
            System.out.print("Ask a policy question (or 'exit'): ");
            String q = reader.readLine();
            if (q == null || q.trim().equalsIgnoreCase("exit")) break;

            CompletableFuture<String> answerFuture = new CompletableFuture<>();
            system.tell(new RootCommand.UserQuestion(q, answerFuture));
            String answer = answerFuture.get();
            System.out.println("\n---\nAnswer:\n" + answer + "\n---\n");
        }
        system.terminate();
    }

    // This is the protocol for the root guardian actor
    public interface RootCommand extends Serializable {
        record UserQuestion(String query, CompletableFuture<String> replyTo) implements RootCommand {}
    }

    // The guardian/root actor: holds all references
    public static class PolicyBotRoot extends AbstractBehavior<RootCommand> {
        private final ActorRef<LoggerActor.LogMsg> logger;
        private final ActorRef<PolicySearchActor.SearchMsg> search;
        private final ActorRef<LLMActor.LLMMsg> llm;

        public static Behavior<RootCommand> create(String openAiKey) {
            return Behaviors.setup(ctx -> new PolicyBotRoot(ctx, openAiKey));
        }

        public PolicyBotRoot(ActorContext<RootCommand> ctx, String openAiKey) {
            super(ctx);
            logger = ctx.spawn(LoggerActor.create(), "Logger");
            search = ctx.spawn(PolicySearchActor.create(), "PolicySearch");
            llm = ctx.spawn(LLMActor.create(openAiKey), "LLM");
        }

        // ONLY ONE createReceive method!
        @Override
        public Receive<RootCommand> createReceive() {
            return newReceiveBuilder()
                    .onMessage(RootCommand.UserQuestion.class, this::onUserQuestion)
                    .onMessage(SearchDone.class, this::onSearchDone)
                    .onMessage(LLMComplete.class, this::onLLMComplete)
                    .build();
        }

        private Behavior<RootCommand> onUserQuestion(RootCommand.UserQuestion msg) {
            // Step 1: ask search
            search.tell(new PolicySearchActor.Query(msg.query, getContext().messageAdapter(
                    PolicySearchActor.Response.class, resp -> new SearchDone(msg.query, resp.results, msg.replyTo)
            )));
            return this;
        }

        // Internal protocol for chaining search → LLM
        private static class SearchDone implements RootCommand {
            final String query;
            final List<PolicySearchActor.Match> matches;
            final CompletableFuture<String> replyTo;
            SearchDone(String query, List<PolicySearchActor.Match> matches, CompletableFuture<String> replyTo) {
                this.query = query;
                this.matches = matches;
                this.replyTo = replyTo;
            }
        }

        private Behavior<RootCommand> onSearchDone(SearchDone msg) {
            llm.tell(new LLMActor.Summarize(msg.query, msg.matches, getContext().messageAdapter(
                    LLMActor.LLMResponse.class, resp -> new LLMComplete(resp.answer, msg.replyTo)
            )));
            return this;
        }

        private static class LLMComplete implements RootCommand {
            final String answer;
            final CompletableFuture<String> replyTo;
            LLMComplete(String answer, CompletableFuture<String> replyTo) {
                this.answer = answer; this.replyTo = replyTo;
            }
        }

        private Behavior<RootCommand> onLLMComplete(LLMComplete msg) {
            msg.replyTo.complete(msg.answer);
            return this;
        }
    }

}
