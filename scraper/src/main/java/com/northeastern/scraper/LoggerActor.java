package com.northeastern.scraper;

import akka.actor.typed.*;
import akka.actor.typed.javadsl.*;

public class LoggerActor extends AbstractBehavior<LoggerActor.LogMsg> {

    public interface LogMsg {}

    public static class Log implements LogMsg {
        public final String msg;
        public Log(String msg) { this.msg = msg; }
    }

    /** Log then forward a typed message to a target actor. */
    public static class LogAndForward<T> implements LogMsg {
        public final String msg;
        public final ActorRef<T> target;
        public final T message;
        public LogAndForward(String msg, ActorRef<T> target, T message) {
            this.msg = msg; this.target = target; this.message = message;
        }
    }

    public static Behavior<LogMsg> create() { return Behaviors.setup(LoggerActor::new); }
    private LoggerActor(ActorContext<LogMsg> ctx) { super(ctx); }

    @Override
    public Receive<LogMsg> createReceive() {
        return newReceiveBuilder()
                .onMessage(Log.class, m -> { System.out.println("[LOG] " + m.msg); return this; })
                .onMessage(LogAndForward.class, m -> {
                    System.out.println("[LOG] " + m.msg);
                    m.target.tell(m.message);
                    return this;
                })
                .build();
    }
}
