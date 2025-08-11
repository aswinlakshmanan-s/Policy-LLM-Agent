package com.northeastern.scraper;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.cluster.typed.Cluster;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Logging service actor that handles distributed logging across the cluster.
 * Demonstrates tell and forward patterns for logging operations.
 */
public class PolicyLogger extends AbstractBehavior<PolicyLogger.Command> {

    // Command messages
    public interface Command extends PolicySerializable {}

    public static final class LogEvent implements Command {
        public final String message;
        public final String nodeType;
        public final String timestamp;

        @JsonCreator
        public LogEvent(
                @JsonProperty("message") String message,
                @JsonProperty("nodeType") String nodeType
        ) {
            this.message = message;
            this.nodeType = nodeType;
            this.timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
        }
    }

    public static final class LogAndForward implements Command {
        public final String logMessage;
        public final ActorRef<String> forwardTo;
        public final String forwardMessage;

        @JsonCreator
        public LogAndForward(
                @JsonProperty("logMessage") String logMessage,
                @JsonProperty("forwardTo") ActorRef<String> forwardTo,
                @JsonProperty("forwardMessage") String forwardMessage
        ) {
            this.logMessage = logMessage;
            this.forwardTo = forwardTo;
            this.forwardMessage = forwardMessage;
        }
    }

    private final String nodeInfo;

    public static Behavior<Command> create() {
        return Behaviors.setup(PolicyLogger::new);
    }

    private PolicyLogger(ActorContext<Command> context) {
        super(context);

        Cluster cluster = Cluster.get(context.getSystem());
        this.nodeInfo = "[" + cluster.selfMember().address() + "]";

        System.out.println("📝 Policy logger started on " + nodeInfo);
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(LogEvent.class, this::onLogEvent)
                .onMessage(LogAndForward.class, this::onLogAndForward)
                .build();
    }

    // DEMONSTRATES: tell pattern for distributed logging
    private Behavior<Command> onLogEvent(LogEvent event) {
        String logEntry = String.format("📋 [%s] [%s] %s %s",
                event.timestamp,
                event.nodeType.toUpperCase(),
                nodeInfo,
                event.message);

        System.out.println(logEntry);

        // In production, write to distributed logging system
        writeToClusterLog(logEntry);

        return this;
    }

    // DEMONSTRATES: forward pattern - log then forward message
    private Behavior<Command> onLogAndForward(LogAndForward event) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
        String logEntry = String.format("🔄 [%s] [FORWARD] %s %s", timestamp, nodeInfo, event.logMessage);

        System.out.println(logEntry);
        writeToClusterLog(logEntry);

        // DEMONSTRATE: forward the message while preserving original sender context
        System.out.println("✅ FORWARD: Delegating message while preserving sender context");
        event.forwardTo.tell(event.forwardMessage);

        return this;
    }

    private void writeToClusterLog(String logEntry) {
        // Simulate writing to distributed log storage
        // In production: ELK stack, database, or distributed file system
    }
}