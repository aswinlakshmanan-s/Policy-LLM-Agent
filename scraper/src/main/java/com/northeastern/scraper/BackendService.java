package com.northeastern.scraper;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;

/**
 * Backend service coordinator - initializes and manages backend actors
 */
public class BackendService extends AbstractBehavior<Void> {

    public static Behavior<Void> create() {
        return Behaviors.setup(BackendService::new);
    }

    private BackendService(ActorContext<Void> context) {
        super(context);

        System.out.println("[BACKEND-SERVICE] Initializing backend services...");

        try {
            // Initialize your existing services
            var embedder = new EmbeddingService(null);
            var qdrant = new QdrantClient("http://127.0.0.1:6333");

            // Test connections
            embedder.embed("test");
            long count = qdrant.countPoints();

            // Create backend actors using your existing classes
            context.spawn(PolicyBackendWorker.create(embedder, qdrant), "policy-worker");
            context.spawn(PolicyLLMService.create(), "llm-service");
            context.spawn(PolicyLogger.create(), "policy-logger");

            System.out.println("[BACKEND-SERVICE] ✅ All services ready. Policies: " + count);

        } catch (Exception e) {
            System.err.println("[BACKEND-SERVICE] ⚠️ Full services failed: " + e.getMessage());
            System.err.println("[BACKEND-SERVICE] Starting limited services...");

            // Fallback: limited services
            context.spawn(PolicyBackendWorker.createLimited(), "policy-worker");
            context.spawn(PolicyLLMService.create(), "llm-service");
            context.spawn(PolicyLogger.create(), "policy-logger");

            System.out.println("[BACKEND-SERVICE] ⚠️ Limited services started");
        }
    }

    @Override
    public Receive<Void> createReceive() {
        return newReceiveBuilder().build();
    }
}