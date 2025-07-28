package com.northeastern.scraper;

import akka.actor.typed.*;
import akka.actor.typed.javadsl.*;

public class LoggerActor extends AbstractBehavior<LoggerActor.LogMsg> {
    public interface LogMsg {}
    public static class Log implements LogMsg {
        public final String msg;
        public Log(String msg) { this.msg = msg; }
    }

    public static Behavior<LogMsg> create() {
        return Behaviors.setup(LoggerActor::new);
    }
    private LoggerActor(ActorContext<LogMsg> ctx) { super(ctx); }

    @Override
    public Receive<LogMsg> createReceive() {
        return newReceiveBuilder()
                .onMessage(Log.class, log -> {
                    System.out.println("[LOG] " + log.msg);
                    return this;
                })
                .build();
    }
}
