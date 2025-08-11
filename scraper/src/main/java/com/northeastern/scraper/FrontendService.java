package com.northeastern.scraper;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;

/**
 * Frontend service coordinator - simple wrapper for PolicyFrontend
 */
public class FrontendService extends AbstractBehavior<Void> {

    public static Behavior<Void> create() {
        return Behaviors.setup(FrontendService::new);
    }

    private FrontendService(ActorContext<Void> context) {
        super(context);

        System.out.println("[FRONTEND-SERVICE] Starting user interface...");

        // Create your PolicyFrontend actor
        context.spawn(PolicyFrontend.create(), "policy-frontend");

        System.out.println("[FRONTEND-SERVICE] ✅ Frontend ready for user interaction");
    }

    @Override
    public Receive<Void> createReceive() {
        return newReceiveBuilder().build();
    }
}