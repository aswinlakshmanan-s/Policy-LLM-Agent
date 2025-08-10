package com.northeastern.scraper;

import akka.actor.typed.*;
import akka.actor.typed.javadsl.*;
import ai.djl.translate.TranslateException;

import java.io.Serializable;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class PolicySearchActor extends AbstractBehavior<PolicySearchActor.SearchMsg> {
    public interface SearchMsg extends Serializable {}

    public static class Query implements SearchMsg {
        public final String query;
        public final ActorRef<Response> replyTo;
        public Query(String query, ActorRef<Response> replyTo) { this.query = query; this.replyTo = replyTo; }
    }

    public static class Response implements Serializable {
        public final List<QdrantClient.PolicyMatch> results;
        public Response(List<QdrantClient.PolicyMatch> results) { this.results = results; }
    }

    private final QdrantClient qdrant;
    private final EmbeddingService embedder;

    public static Behavior<SearchMsg> create(QdrantClient client, EmbeddingService embedder) {
        return Behaviors.setup(ctx -> new PolicySearchActor(ctx, client, embedder));
    }

    private PolicySearchActor(ActorContext<SearchMsg> ctx, QdrantClient client, EmbeddingService embedder) {
        super(ctx);
        this.qdrant   = client;
        this.embedder = embedder;
    }

    @Override
    public Receive<SearchMsg> createReceive() {
        return newReceiveBuilder()
                .onMessage(Query.class, this::onQuery)
                .build();
    }

    private Behavior<SearchMsg> onQuery(Query msg) {
        System.out.println("[Search] received query: " + msg.query);

        // Offload the heavy embedding + search so we don't block the actor thread.
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        System.out.println("[Search] Embedding start");
                        long t0 = System.nanoTime();
                        float[] embedding = embedder.embed(msg.query);
                        System.out.println("[Search] Embedding done in " + ((System.nanoTime()-t0)/1_000_000) + " ms");

                        System.out.println("[Search] Qdrant search start");
                        List<QdrantClient.PolicyMatch> results = qdrant.search(embedding, 3);
                        System.out.println("[Search] Qdrant search done, hits=" + results.size());
                        return results;
                    } catch (TranslateException te) {
                        te.printStackTrace();
                        return java.util.List.<QdrantClient.PolicyMatch>of();
                    } catch (Exception e) {
                        e.printStackTrace();
                        return java.util.List.<QdrantClient.PolicyMatch>of();
                    }
                })
                .whenComplete((results, err) -> {
                    if (err != null) {
                        System.out.println("[Search] failed: " + err);
                        msg.replyTo.tell(new Response(java.util.List.of()));
                    } else {
                        msg.replyTo.tell(new Response(results));
                    }
                });

        return this;
    }
}
