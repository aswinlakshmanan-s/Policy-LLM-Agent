package com.northeastern.scraper;

import akka.actor.typed.*;
import akka.actor.typed.javadsl.*;

import com.google.gson.*;
import org.apache.hc.client5.http.fluent.Request;
import org.apache.hc.core5.http.ContentType;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

public class LLMActor extends AbstractBehavior<LLMActor.LLMMsg> {

    public interface LLMMsg {}

    public static class Summarize implements LLMMsg {
        public final String userQuery;
        public final List<PolicySearchActor.Match> matches;
        public final ActorRef<LLMResponse> replyTo;

        public Summarize(String userQuery, List<PolicySearchActor.Match> matches, ActorRef<LLMResponse> replyTo) {
            this.userQuery = userQuery;
            this.matches = matches;
            this.replyTo = replyTo;
        }
    }

    public static class LLMResponse {
        public final String answer;
        public LLMResponse(String answer) {
            this.answer = answer;
        }
    }

    private final String apiKey;
    private static final String GENERATE_ENDPOINT = "https://api.cohere.ai/generate";

    public static Behavior<LLMMsg> create(String apiKey) {
        return Behaviors.setup(ctx -> new LLMActor(ctx, apiKey));
    }

    private LLMActor(ActorContext<LLMMsg> ctx, String apiKey) {
        super(ctx);
        this.apiKey = apiKey;
    }

    @Override
    public Receive<LLMMsg> createReceive() {
        return newReceiveBuilder()
                .onMessage(Summarize.class, this::onSummarize)
                .build();
    }

    private Behavior<LLMMsg> onSummarize(Summarize msg) {
        String systemPrompt = "You are a helpful assistant answering questions about Northeastern University policies.";

        // Limit policy snippets to 2 to avoid prompt length issues
        String context = msg.matches.stream()
                .limit(2)
                .map(m -> m.filename + ": " + m.snippet)
                .collect(Collectors.joining("\n"));

        String prompt = systemPrompt + "\n\nUser question: " + msg.userQuery +
                "\nRelevant policies:\n" + context + "\nAnswer:";

        JsonObject payload = new JsonObject();
        payload.addProperty("model", "command-xlarge");  // switched to stable model
        payload.addProperty("prompt", prompt);
        payload.addProperty("max_tokens", 200);
        payload.addProperty("temperature", 0.2);

        // Log the payload for debugging
        System.out.println("[LLMActor] Sending payload: " + payload);

        try {
            String response = Request.post(GENERATE_ENDPOINT)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .bodyString(payload.toString(), ContentType.APPLICATION_JSON)
                    .execute()
                    .returnContent()
                    .asString();

            // Log the raw response
            System.out.println("[LLMActor] Raw response: " + response);

            JsonObject respJson = JsonParser.parseString(response).getAsJsonObject();
            String answer = respJson.getAsJsonArray("generations")
                    .get(0).getAsJsonObject()
                    .get("text").getAsString();

            msg.replyTo.tell(new LLMResponse(answer));
        } catch (IOException e) {
            // Log full error for diagnosis
            System.err.println("[LLMActor] Error during generation: " + e.getMessage());
            msg.replyTo.tell(new LLMResponse("Error generating response: " + e.getMessage()));
        }

        return this;
    }
}
