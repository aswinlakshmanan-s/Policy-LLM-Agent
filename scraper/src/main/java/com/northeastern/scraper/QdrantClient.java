package com.northeastern.scraper;

import com.google.gson.*;
import org.apache.hc.client5.http.fluent.Request;
import org.apache.hc.client5.http.HttpResponseException;
import org.apache.hc.core5.http.ContentType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class QdrantClient {
    private final String url; // e.g. "http://localhost:6333"
    private final String collection = "policies";
    private static final Gson gson = new Gson();

    public QdrantClient(String url) {
        this.url = url;
    }

    /**
     * Checks if collection exists. If not, creates it with given vector size and cosine distance.
     *
     * @param vectorSize dimension of vectors to be stored (embedding size)
     * @throws IOException on HTTP or IO failures.
     */
    public void createCollectionIfNotExists(int vectorSize) throws IOException {
        try {
            Request.get(url + "/collections/" + collection)
                    .execute()
                    .discardContent();
            System.out.println("[QdrantClient] Collection '" + collection + "' already exists.");
        } catch (HttpResponseException e) {
            if (e.getStatusCode() == 404) {
                System.out.println("[QdrantClient] Collection '" + collection + "' not found. Creating...");
                JsonObject vectors = new JsonObject();
                vectors.addProperty("size", vectorSize);
                vectors.addProperty("distance", "Cosine");

                JsonObject body = new JsonObject();
                body.add("vectors", vectors);

                Request.put(url + "/collections/" + collection)
                        .bodyString(gson.toJson(body), ContentType.APPLICATION_JSON)
                        .execute();
                System.out.println("[QdrantClient] Collection '" + collection + "' created successfully.");
            } else {
                System.err.println("[QdrantClient] Error checking collection existence (status " +
                        e.getStatusCode() + "): " + e.getMessage());
                throw e;
            }
        }
    }

    /**
     * Upserts a policy vector point into the collection.
     *
     * @param id        Unique string ID for the point (e.g., filename).
     * @param title     Policy title metadata.
     * @param text      Full policy text metadata.
     * @param embedding Embedding vector as float array.
     * @throws IOException on HTTP or IO failures.
     */
    public void upsertPolicy(String id, String title, String text, float[] embedding) throws IOException {
        try {
            JsonObject point = new JsonObject();
            point.addProperty("id", id);

            JsonObject payload = new JsonObject();
            payload.addProperty("title", title);
            payload.addProperty("text", text);
            point.add("payload", payload);

            JsonArray vectorArray = new JsonArray();
            for (float v : embedding) {
                vectorArray.add(v);
            }
            point.add("vector", vectorArray);

            JsonArray points = new JsonArray();
            points.add(point);

            JsonObject reqBody = new JsonObject();
            reqBody.add("points", points);

            Request.put(url + "/collections/" + collection + "/points/upsert")
                    .bodyString(gson.toJson(reqBody), ContentType.APPLICATION_JSON)
                    .execute();
            System.out.println("[QdrantClient] Upserted policy point with ID: " + id);
        } catch (Exception e) {
            System.err.println("[QdrantClient] Failed to upsert policy " + id + ": " + e.getMessage());
            throw e;
        }
    }

    /**
     * Searches for top K similar policies using the query embedding.
     *
     * @param queryEmbedding embedding vector for the query.
     * @param topK           number of top results to retrieve.
     * @return list of PolicyMatch objects with title, text, and similarity score.
     * @throws IOException on HTTP or IO failures.
     */
    public List<PolicyMatch> search(float[] queryEmbedding, int topK) throws IOException {
        List<PolicyMatch> matches = new ArrayList<>();
        try {
            JsonArray vectorArray = new JsonArray();
            for (float v : queryEmbedding) {
                vectorArray.add(v);
            }

            JsonObject body = new JsonObject();
            body.add("vector", vectorArray);
            body.addProperty("top", topK);

            String response = Request.post(url + "/collections/" + collection + "/points/search")
                    .bodyString(gson.toJson(body), ContentType.APPLICATION_JSON)
                    .execute()
                    .returnContent()
                    .asString();

            JsonObject parsed = JsonParser.parseString(response).getAsJsonObject();
            if (!parsed.has("result") || parsed.get("result").isJsonNull()) {
                System.out.println("[QdrantClient] No search results found.");
                return matches;
            }

            JsonArray resultsArray = parsed.getAsJsonArray("result");
            for (JsonElement element : resultsArray) {
                JsonObject pointObj = element.getAsJsonObject();
                JsonObject payload = pointObj.getAsJsonObject("payload");
                if (payload == null) continue; // Defensive

                String title = payload.has("title") ? payload.get("title").getAsString() : "<no title>";
                String text = payload.has("text") ? payload.get("text").getAsString() : "<no text>";
                float score = pointObj.has("score") ? pointObj.get("score").getAsFloat() : 0f;

                matches.add(new PolicyMatch(title, text, score));
            }
        } catch (Exception e) {
            System.err.println("[QdrantClient] Search failed: " + e.getMessage());
            throw e;
        }
        return matches;
    }

    public static class PolicyMatch {
        public final String title;
        public final String text;
        public final float score;

        public PolicyMatch(String title, String text, float score) {
            this.title = title;
            this.text = text;
            this.score = score;
        }

        @Override
        public String toString() {
            return "→ " + title + " (score: " + score + ")\n" + text;
        }
    }
}
