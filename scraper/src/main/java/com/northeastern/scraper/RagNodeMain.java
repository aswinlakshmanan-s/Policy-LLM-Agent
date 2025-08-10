package com.northeastern.scraper;

import akka.actor.typed.*;
import akka.actor.typed.javadsl.*;
import akka.actor.typed.receptionist.Receptionist;

public class RagNodeMain {
    public static void main(String[] args) {
        ActorSystem<Void> system = ActorSystem.create(RagGuardian.create(), "PolicyCluster");
    }

    static class RagGuardian extends AbstractBehavior<Void> {
        static Behavior<Void> create() { return Behaviors.setup(RagGuardian::new); }

        private RagGuardian(ActorContext<Void> ctx) {
            super(ctx);

            var embeddings = new EmbeddingService(null);
            var qdrant     = new QdrantClient("http://localhost:6333");

            ActorRef<LoggerActor.LogMsg> logger =
                    ctx.spawn(LoggerActor.create(), "Logger");
            ActorRef<PolicySearchActor.SearchMsg> search =
                    ctx.spawn(PolicySearchActor.create(qdrant, embeddings), "PolicySearch");
            ActorRef<LLMActor.LLMMsg> llm =
                    ctx.spawn(LLMActor.create(), "LLM");

            ctx.getSystem().receptionist().tell(Receptionist.register(ServiceKeys.LOGGER_KEY, logger));
            ctx.getSystem().receptionist().tell(Receptionist.register(ServiceKeys.SEARCH_KEY, search));
            ctx.getSystem().receptionist().tell(Receptionist.register(ServiceKeys.LLM_KEY, llm));

            ctx.getLog().info("RAG node up: logger/search/llm registered.");
        }

        @Override public Receive<Void> createReceive() { return newReceiveBuilder().build(); }
    }
}
