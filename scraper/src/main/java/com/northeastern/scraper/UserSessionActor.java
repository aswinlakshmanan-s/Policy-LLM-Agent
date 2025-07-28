package com.northeastern.scraper;

import akka.actor.typed.*;
import akka.actor.typed.javadsl.*;
import java.time.Duration;
import java.util.List;


public class UserSessionActor extends AbstractBehavior<UserSessionActor.UserMsg> {
    public interface UserMsg {}
    public static class UserQuery implements UserMsg {
        public final String question;
        public UserQuery(String question) { this.question = question; }
    }
    private interface InternalMsg extends UserMsg {}
    private static class SearchResultMsg implements InternalMsg {
        final List<PolicySearchActor.Match> results;
        final Throwable error;
        SearchResultMsg(List<PolicySearchActor.Match> results, Throwable error) {
            this.results = results; this.error = error;
        }
    }

    private final ActorRef<PolicySearchActor.SearchMsg> searchActor;
    private final ActorRef<LoggerActor.LogMsg> logger;
    private final ActorRef<PolicySearchActor.Response> searchResultAdapter;

    public static Behavior<UserMsg> create(
            ActorRef<PolicySearchActor.SearchMsg> searchActor,
            ActorRef<LoggerActor.LogMsg> logger) {
        return Behaviors.setup(ctx -> new UserSessionActor(ctx, searchActor, logger));
    }

    private UserSessionActor(ActorContext<UserMsg> ctx,
                             ActorRef<PolicySearchActor.SearchMsg> searchActor,
                             ActorRef<LoggerActor.LogMsg> logger) {
        super(ctx);
        this.searchActor = searchActor;
        this.logger = logger;
        this.searchResultAdapter = ctx.messageAdapter(
                PolicySearchActor.Response.class,
                resp -> new SearchResultMsg(resp.results, null)
        );
    }

    @Override
    public Receive<UserMsg> createReceive() {
        return newReceiveBuilder()
                .onMessage(UserQuery.class, this::onUserQuery)
                .onMessage(SearchResultMsg.class, this::onSearchResult)
                .build();
    }

    private Behavior<UserMsg> onUserQuery(UserQuery msg) {
        searchActor.tell(new PolicySearchActor.Query(msg.question, searchResultAdapter));
        return this;
    }
    private Behavior<UserMsg> onSearchResult(SearchResultMsg msg) {
        if (msg.results != null) {
            logger.tell(new LoggerActor.Log("User got: " + msg.results));
            for (PolicySearchActor.Match m : msg.results) System.out.println(m);
        } else {
            logger.tell(new LoggerActor.Log("Search failed: " + msg.error));
        }
        return this;
    }
}
