package com.northeastern.scraper;

import akka.actor.typed.*;
import akka.actor.typed.javadsl.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class PolicySearchActor extends AbstractBehavior<PolicySearchActor.SearchMsg> {
    public interface SearchMsg {}
    public static class Query implements SearchMsg {
        public final String query;
        public final ActorRef<Response> replyTo;
        public Query(String query, ActorRef<Response> replyTo) {
            this.query = query; this.replyTo = replyTo;
        }
    }
    public static class Response {
        public final List<Match> results;
        public Response(List<Match> results) { this.results = results; }
    }
    public static class Match {
        public final String filename;
        public final String snippet;
        public Match(String filename, String snippet) {
            this.filename = filename; this.snippet = snippet;
        }
    }

    private final List<File> policyFiles;

    public static Behavior<SearchMsg> create() {
        return Behaviors.setup(PolicySearchActor::new);
    }

    private PolicySearchActor(ActorContext<SearchMsg> ctx) {
        super(ctx);
        File dir = new File("data/policies");
        policyFiles = Arrays.asList(Objects.requireNonNull(dir.listFiles((d, n) -> n.endsWith(".txt"))));
    }

    @Override
    public Receive<SearchMsg> createReceive() {
        return newReceiveBuilder()
                .onMessage(Query.class, this::onQuery)
                .build();
    }

    private Behavior<SearchMsg> onQuery(Query msg) {
        String q = msg.query.toLowerCase();
        List<Match> hits = new ArrayList<>();
        for (File file : policyFiles) {
            try {
                String text = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                if (text.toLowerCase().contains(q)) {
                    // Find the line/snippet containing the keyword
                    for (String line : text.split("\n")) {
                        if (line.toLowerCase().contains(q)) {
                            hits.add(new Match(file.getName(), line.trim()));
                        }
                    }
                }
            } catch (IOException ignored) {}
        }
        msg.replyTo.tell(new Response(hits.stream().limit(3).collect(Collectors.toList())));
        return this;
    }
}
