package com.northeastern.scraper;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;

import java.io.Serializable;
import java.util.List;

public class LLMActor extends AbstractBehavior<LLMActor.LLMMsg> {

    // Protocol
    public interface LLMMsg extends Serializable {}

    public static class LLMResponse implements Serializable {
        public final String answer;
        public LLMResponse(String answer) { this.answer = answer; }
    }

    public static class Summarize implements LLMMsg {
        public final String question;
        public final List<QdrantClient.PolicyMatch> results;
        public final akka.actor.typed.ActorRef<LLMResponse> replyTo;

        public Summarize(String question, List<QdrantClient.PolicyMatch> results, akka.actor.typed.ActorRef<LLMResponse> replyTo) {
            this.question = question;
            this.results = results;
            this.replyTo = replyTo;
        }
    }

    public static Behavior<LLMMsg> create() {
        return Behaviors.setup(LLMActor::new);
    }

    private LLMActor(ActorContext<LLMMsg> ctx) {
        super(ctx);
        System.out.println("[LLM] ✅ NO API MODE - Direct policy responses, no 429 errors!");
    }

    @Override
    public Receive<LLMMsg> createReceive() {
        return newReceiveBuilder()
                .onMessage(Summarize.class, this::onSummarize)
                .build();
    }

    // THE KEY FIX: No OpenAI calls whatsoever
    private Behavior<LLMMsg> onSummarize(Summarize msg) {
        System.out.println("[LLM] Processing: " + msg.question);
        System.out.println("[LLM] Policies found: " + msg.results.size());

        // NO API CALLS - just format the policy data directly
        String answer = createDirectResponse(msg.question, msg.results);

        System.out.println("[LLM] ✅ Direct response created");
        msg.replyTo.tell(new LLMResponse(answer));

        return this;
    }

    private String createDirectResponse(String question, List<QdrantClient.PolicyMatch> results) {
        if (results.isEmpty()) {
            return "No relevant policies found for: \"" + question + "\"\n\n" +
                    "Try rephrasing or ask about: housing, academics, conduct, employment, insurance";
        }

        StringBuilder response = new StringBuilder();
        response.append("📋 NORTHEASTERN UNIVERSITY POLICIES\n");
        response.append("Question: ").append(question).append("\n\n");

        for (int i = 0; i < results.size() && i < 3; i++) {
            QdrantClient.PolicyMatch match = results.get(i);

            response.append("Policy ").append(i + 1).append(": ").append(match.title).append("\n");
            response.append("Relevance: ").append(String.format("%.1f%%", match.score * 100)).append("\n");

            if (match.text != null) {
                String excerpt = getKeyExcerpt(match.text, question);
                response.append("Content: ").append(excerpt).append("\n");
            }
            response.append("\n");
        }

        return response.toString();
    }

    private String getKeyExcerpt(String text, String question) {
        // Simple extraction - no complex processing
        String clean = text.trim().replaceAll("\\s+", " ");

        if (clean.length() <= 300) {
            return clean;
        }

        // Find relevant part based on question keywords
        String[] words = question.toLowerCase().split("\\s+");
        String lowerText = clean.toLowerCase();

        int bestStart = 0;
        int maxMatches = 0;

        // Sliding window to find best excerpt
        for (int start = 0; start < clean.length() - 200; start += 50) {
            int end = Math.min(start + 300, clean.length());
            String window = lowerText.substring(start, end);

            int matches = 0;
            for (String word : words) {
                if (word.length() > 2 && window.contains(word)) {
                    matches++;
                }
            }

            if (matches > maxMatches) {
                maxMatches = matches;
                bestStart = start;
            }
        }

        int end = Math.min(bestStart + 300, clean.length());
        String excerpt = clean.substring(bestStart, end);

        // Clean up excerpt boundaries
        if (bestStart > 0) excerpt = "..." + excerpt;
        if (end < clean.length()) excerpt = excerpt + "...";

        return excerpt;
    }
}