package com.northeastern.scraper;

import akka.actor.typed.*;
import akka.actor.typed.javadsl.*;

import java.io.Serializable;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class UserSessionActor extends AbstractBehavior<UserSessionActor.UserMsg> {
    public interface UserMsg extends Serializable {}

    public static class UserQuery implements UserMsg {
        public final String question;
        public UserQuery(String question) { this.question = question; }
    }

    private interface InternalMsg extends UserMsg {}
    private static class SearchResultMsg implements InternalMsg {
        final String question;
        final List<QdrantClient.PolicyMatch> results;
        SearchResultMsg(String question, List<QdrantClient.PolicyMatch> results) {
            this.question = question;
            this.results = results;
        }
    }
    private static class LLMResultMsg implements InternalMsg {
        final String answer;
        LLMResultMsg(String answer) { this.answer = answer; }
    }

    private final ActorRef<PolicySearchActor.SearchMsg> searchActor;
    private final ActorRef<LLMActor.LLMMsg> llmActor;
    private final ActorRef<LoggerActor.LogMsg> logger;
    private final ActorRef<PolicySearchActor.Response> searchResultAdapter;
    private final ActorRef<LLMActor.LLMResponse> llmResultAdapter;

    // Add callback support
    private final ActorRef<PolicyBotMain.RootCommand> rootActor;
    private final CompletableFuture<String> answerCallback;

    private String currentQuestion;

    // Original create method (for standalone use)
    public static Behavior<UserMsg> create(
            ActorRef<PolicySearchActor.SearchMsg> searchActor,
            ActorRef<LLMActor.LLMMsg> llmActor,
            ActorRef<LoggerActor.LogMsg> logger) {
        return Behaviors.setup(ctx -> new UserSessionActor(ctx, searchActor, llmActor, logger, null, null));
    }

    // New create method with callback support
    public static Behavior<UserMsg> createWithCallback(
            ActorRef<PolicySearchActor.SearchMsg> searchActor,
            ActorRef<LLMActor.LLMMsg> llmActor,
            ActorRef<LoggerActor.LogMsg> logger,
            ActorRef<PolicyBotMain.RootCommand> rootActor,
            CompletableFuture<String> answerCallback) {
        return Behaviors.setup(ctx -> new UserSessionActor(ctx, searchActor, llmActor, logger, rootActor, answerCallback));
    }

    private UserSessionActor(ActorContext<UserMsg> ctx,
                             ActorRef<PolicySearchActor.SearchMsg> searchActor,
                             ActorRef<LLMActor.LLMMsg> llmActor,
                             ActorRef<LoggerActor.LogMsg> logger,
                             ActorRef<PolicyBotMain.RootCommand> rootActor,
                             CompletableFuture<String> answerCallback) {
        super(ctx);
        this.searchActor = searchActor;
        this.llmActor = llmActor;
        this.logger = logger;
        this.rootActor = rootActor;
        this.answerCallback = answerCallback;

        this.searchResultAdapter = ctx.messageAdapter(PolicySearchActor.Response.class,
                resp -> new SearchResultMsg(currentQuestion, resp.results));

        this.llmResultAdapter = ctx.messageAdapter(LLMActor.LLMResponse.class,
                resp -> new LLMResultMsg(resp.answer));
    }

    @Override
    public Receive<UserMsg> createReceive() {
        return newReceiveBuilder()
                .onMessage(UserQuery.class, this::onUserQuery)
                .onMessage(SearchResultMsg.class, this::onSearchResult)
                .onMessage(LLMResultMsg.class, this::onLLMResult)
                .build();
    }

    private Behavior<UserMsg> onUserQuery(UserQuery msg) {
        this.currentQuestion = msg.question;
        logger.tell(new LoggerActor.Log("Received user query: " + msg.question));
        searchActor.tell(new PolicySearchActor.Query(msg.question, searchResultAdapter));
        return this;
    }

    private Behavior<UserMsg> onSearchResult(SearchResultMsg msg) {
        logger.tell(new LoggerActor.LogAndForward<>(
                "Forwarding Summarize to LLM...",
                llmActor,
                new LLMActor.Summarize(msg.question, msg.results, llmResultAdapter)
        ));
        return this;
    }

    private Behavior<UserMsg> onLLMResult(LLMResultMsg msg) {
        logger.tell(new LoggerActor.Log("Final Answer: " + msg.answer));
        System.out.println("Final Answer: " + msg.answer);

        // Complete the callback if provided
        if (answerCallback != null) {
            answerCallback.complete(msg.answer);
        }

        // Or send back to root actor
        if (rootActor != null && answerCallback != null) {
            rootActor.tell(new PolicyBotMain.RootCommand.LlmDone(msg.answer, answerCallback));
        }

        return Behaviors.stopped(); // Stop this session actor after completion
    }
}