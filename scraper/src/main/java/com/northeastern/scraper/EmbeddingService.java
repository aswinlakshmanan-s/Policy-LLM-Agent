package com.northeastern.scraper;

import com.google.gson.*;
import org.apache.hc.client5.http.fluent.Request;
import org.apache.hc.core5.http.ContentType;

import java.io.IOException;

public class EmbeddingService {
    private final String apiKey;
    private static final String ENDPOINT = "https://api.cohere.ai/v1/embed";

    public EmbeddingService(String apiKey) {
        this.apiKey = apiKey;
    }

    public float[] embed(String text) throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", "large");  // Confirm valid model
        JsonArray texts = new JsonArray();
        texts.add(text);
        payload.add("texts", texts);

        String response = Request.post(ENDPOINT)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .bodyString(payload.toString(), ContentType.APPLICATION_JSON)
                .execute().returnContent().asString();

        JsonObject respJson = JsonParser.parseString(response).getAsJsonObject();

        if (!respJson.has("embeddings")) {
            throw new IOException("No embeddings returned from Cohere API");
        }

        JsonArray embeddingArray = respJson.getAsJsonArray("embeddings").get(0).getAsJsonArray();

        float[] embedding = new float[embeddingArray.size()];
        for (int i = 0; i < embeddingArray.size(); i++) {
            embedding[i] = embeddingArray.get(i).getAsFloat();
        }
        return embedding;
    }
}
