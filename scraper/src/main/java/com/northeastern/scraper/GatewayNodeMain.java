package com.northeastern.scraper;

import akka.actor.typed.*;
import akka.actor.typed.javadsl.*;
import akka.actor.typed.receptionist.Receptionist;
import akka.actor.typed.receptionist.Receptionist.Listing;
import akka.actor.typed.javadsl.AskPattern;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class GatewayNodeMain {

    public static void main(String[] args) throws Exception {
        ActorSystem<GatewayGuardian.Cmd> system =
                ActorSystem.create(GatewayGuardian.create(), "PolicyCluster");

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        while (true) {
            System.out.print("Ask a policy question (or 'exit'): ");
            String q = reader.readLine();
            if (q == null || q.trim().equalsIgnoreCase("exit")) break;

            CompletableFuture<String> fut = new CompletableFuture<>();
            system.tell(new GatewayGuardian.UserQuestion(q, fut));
            System.out.println("\n---\nAnswer:\n" + fut.get() + "\n---\n");
        }
        system.terminate();
    }

    static class GatewayGuardian extends AbstractBehavior<GatewayGuardian.Cmd> {
        interface Cmd {}

        record UserQuestion(String query, CompletableFuture<String> replyTo) implements Cmd {}

        static final class LoggerListing implements Cmd { final Listing listing; LoggerListing(Listing l){this.listing=l;} }
        static final class SearchListing implements Cmd { final Listing listing; SearchListing(Listing l){this.listing=l;} }
        static final class LLMListing    implements Cmd { final Listing listing; LLMListing(Listing l){this.listing=l;} }

        private ActorRef<LoggerActor.LogMsg> loggerRef;
        private ActorRef<PolicySearchActor.SearchMsg> searchRef;
        private ActorRef<LLMActor.LLMMsg> llmRef;

        private final Duration timeout = Duration.ofSeconds(20);

        static Behavior<Cmd> create() { return Behaviors.setup(GatewayGuardian::new); }

        private GatewayGuardian(ActorContext<Cmd> ctx) {
            super(ctx);

            ActorRef<Listing> loggerAdapter = ctx.messageAdapter(Listing.class, LoggerListing::new);
            ActorRef<Listing> searchAdapter = ctx.messageAdapter(Listing.class, SearchListing::new);
            ActorRef<Listing> llmAdapter    = ctx.messageAdapter(Listing.class, LLMListing::new);

            ctx.getSystem().receptionist().tell(Receptionist.subscribe(ServiceKeys.LOGGER_KEY, loggerAdapter));
            ctx.getSystem().receptionist().tell(Receptionist.subscribe(ServiceKeys.SEARCH_KEY, searchAdapter));
            ctx.getSystem().receptionist().tell(Receptionist.subscribe(ServiceKeys.LLM_KEY, llmAdapter));

            ctx.getLog().info("Gateway waiting for services (logger/search/llm)...");
        }

        private <T> ActorRef<T> pickOne(java.util.Set<ActorRef<T>> set) {
            Optional<ActorRef<T>> first = set.stream().findFirst();
            return first.orElse(null);
        }

        @Override
        public Receive<Cmd> createReceive() {
            return newReceiveBuilder()
                    .onMessage(LoggerListing.class, msg -> { loggerRef = pickOne(msg.listing.getServiceInstances(ServiceKeys.LOGGER_KEY)); return this; })
                    .onMessage(SearchListing.class, msg -> { searchRef = pickOne(msg.listing.getServiceInstances(ServiceKeys.SEARCH_KEY)); return this; })
                    .onMessage(LLMListing.class,    msg -> { llmRef    = pickOne(msg.listing.getServiceInstances(ServiceKeys.LLM_KEY));    return this; })
                    .onMessage(UserQuestion.class, this::onUserQuestion)
                    .build();
        }

        private boolean ready() { return loggerRef != null && searchRef != null && llmRef != null; }

        private Behavior<Cmd> onUserQuestion(UserQuestion msg) {
            if (!ready()) {
                msg.replyTo.complete("Services not ready yet—wait a few seconds and try again.");
                return this;
            }

            var searchFut = AskPattern.ask(
                    searchRef,
                    (ActorRef<PolicySearchActor.Response> r) -> new PolicySearchActor.Query(msg.query, r),
                    timeout,
                    getContext().getSystem().scheduler()
            );

            loggerRef.tell(new LoggerActor.Log("User asked: " + msg.query));

            searchFut.whenComplete((resp, err) -> {
                if (err != null) {
                    msg.replyTo.complete("Search error: " + err.getMessage());
                    return;
                }

                var matches = resp.results;

                ActorRef<LLMActor.LLMResponse> llmReply = getContext().messageAdapter(
                        LLMActor.LLMResponse.class,
                        r -> { msg.replyTo.complete(r.answer); return null; });

                loggerRef.tell(new LoggerActor.LogAndForward(
                        "Forwarding Summarize to LLM via Logger...",
                        llmRef,
                        new LLMActor.Summarize(msg.query, matches, llmReply)
                ));
            });

            return this;
        }
    }
}
