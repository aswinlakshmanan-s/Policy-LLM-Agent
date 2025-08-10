package com.northeastern.scraper;

import akka.actor.typed.*;
import akka.actor.typed.javadsl.*;

import java.io.Serializable;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class MinimalSearchTest extends AbstractBehavior<MinimalSearchTest.TestMsg> {

    public interface TestMsg extends Serializable {}

    public static class DoSearch implements TestMsg {
        public final String query;
        public final ActorRef<SearchResult> replyTo;
        public DoSearch(String query, ActorRef<SearchResult> replyTo) {
            this.query = query;
            this.replyTo = replyTo;
        }
    }

    public static class SearchResult implements Serializable {
        public final String message;
        public SearchResult(String message) { this.message = message; }
    }

    private final QdrantClient qdrant;
    private final EmbeddingService embedder;

    public static Behavior<TestMsg> create() {
        return Behaviors.setup(ctx -> {
            try {
                QdrantClient qdrant = new QdrantClient("http://127.0.0.1:6333");
                EmbeddingService embedder = new EmbeddingService(null);
                return new MinimalSearchTest(ctx, qdrant, embedder);
            } catch (Exception e) {
                throw new RuntimeException("Failed to create test actor", e);
            }
        });
    }

    private MinimalSearchTest(ActorContext<TestMsg> ctx, QdrantClient qdrant, EmbeddingService embedder) {
        super(ctx);
        this.qdrant = qdrant;
        this.embedder = embedder;
        System.out.println("[MinimalTest] Actor created successfully");
    }

    @Override
    public Receive<TestMsg> createReceive() {
        return newReceiveBuilder()
                .onMessage(DoSearch.class, this::onDoSearch)
                .build();
    }

    private Behavior<TestMsg> onDoSearch(DoSearch msg) {
        System.out.println("[MinimalTest] Starting search for: " + msg.query);
        System.out.println("[MinimalTest] ReplyTo: " + msg.replyTo);

        CompletableFuture
                .supplyAsync(() -> {
                    System.out.println("[MinimalTest] Inside async block");
                    try {
                        System.out.println("[MinimalTest] Creating embedding...");
                        float[] embedding = embedder.embed(msg.query);
                        System.out.println("[MinimalTest] Embedding done, size: " + embedding.length);

                        System.out.println("[MinimalTest] Searching Qdrant...");
                        var results = qdrant.search(embedding, 3);
                        System.out.println("[MinimalTest] Search done, results: " + results.size());

                        return "SUCCESS: Found " + results.size() + " results";

                    } catch (Exception e) {
                        System.err.println("[MinimalTest] Exception in async: " + e.getMessage());
                        e.printStackTrace();
                        return "ERROR: " + e.getMessage();
                    }
                })
                .whenComplete((result, error) -> {
                    System.out.println("[MinimalTest] === whenComplete called ===");
                    System.out.println("[MinimalTest] Result: " + result);
                    System.out.println("[MinimalTest] Error: " + error);

                    String message = (error != null) ? "ASYNC_ERROR: " + error.getMessage() : result;

                    System.out.println("[MinimalTest] About to tell replyTo...");
                    try {
                        msg.replyTo.tell(new SearchResult(message));
                        System.out.println("[MinimalTest] Successfully sent result!");
                    } catch (Exception e) {
                        System.err.println("[MinimalTest] Failed to send result: " + e.getMessage());
                        e.printStackTrace();
                    }
                });

        System.out.println("[MinimalTest] Async started, returning from onDoSearch");
        return this;
    }

    // Simple main method to test this actor
    public static void main(String[] args) throws Exception {
        System.out.println("🧪 Starting minimal search actor test...");

        // Create a root behavior that handles both test and result messages
        Behavior<Object> rootBehavior = Behaviors.setup(ctx -> {

            // Spawn the search actor
            ActorRef<TestMsg> searchActor = ctx.spawn(MinimalSearchTest.create(), "SearchActor");

            // Create a simple result handler behavior
            Behavior<SearchResult> resultBehavior = Behaviors.setup(resultCtx ->
                    new AbstractBehavior<SearchResult>(resultCtx) {
                        @Override
                        public Receive<SearchResult> createReceive() {
                            return newReceiveBuilder()
                                    .onMessage(SearchResult.class, result -> {
                                        System.out.println("🎉 RECEIVED RESULT: " + result.message);
                                        getContext().getSystem().terminate();
                                        return this;
                                    })
                                    .build();
                        }
                    }
            );

            ActorRef<SearchResult> resultHandler = ctx.spawn(resultBehavior, "ResultHandler");

            // Send the test query
            searchActor.tell(new DoSearch("visitor policy", resultHandler));

            return Behaviors.ignore();
        });

        ActorSystem<Object> system = ActorSystem.create(rootBehavior, "TestSystem");

        Thread.sleep(10000); // Wait 10 seconds
        if (!system.whenTerminated().isCompleted()) {
            system.terminate();
        }
        System.out.println("Test completed.");
    }
}