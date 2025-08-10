package com.northeastern.scraper;

import com.google.gson.*;
import org.apache.hc.client5.http.HttpResponseException;
import org.apache.hc.client5.http.fluent.Request;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class QdrantClient {
    private final String url; // e.g., "http://localhost:6333"
    private final String collection = "policies";
    private static final Gson gson = new Gson();
    private static final Timeout CONNECT_TIMEOUT = Timeout.ofSeconds(5);
    private static final Timeout RESPONSE_TIMEOUT = Timeout.ofSeconds(20);


    public QdrantClient(String url) {
        this.url = url;
    }

    /** Ensure collection exists with EXACT vectorSize. If mismatch, drop & recreate. */
    public void ensureCollectionExactly(int vectorSize) throws IOException {
        Integer existing = getCurrentVectorSize();
        if (existing == null) {
            createCollection(vectorSize);
            System.out.println("[QdrantClient] Created collection '" + collection + "' with size=" + vectorSize);
            return;
        }
        if (existing != vectorSize) {
            System.out.println("[QdrantClient] Vector size mismatch: existing=" + existing + ", expected=" + vectorSize + ". Recreating...");
            dropCollection();
            createCollection(vectorSize);
            System.out.println("[QdrantClient] Recreated collection '" + collection + "' with size=" + vectorSize);
        } else {
            System.out.println("[QdrantClient] Collection '" + collection + "' already exists with correct size=" + existing);
        }
    }

    /** Backward compat alias. */
    public void createCollectionIfNotExists(int vectorSize) throws IOException {
        ensureCollectionExactly(vectorSize);
    }

    private void createCollection(int vectorSize) throws IOException {
        JsonObject vectors = new JsonObject();
        vectors.addProperty("size", vectorSize);
        vectors.addProperty("distance", "Cosine");

        JsonObject body = new JsonObject();
        body.add("vectors", vectors);

        Request.put(url + "/collections/" + collection)
                .connectTimeout(CONNECT_TIMEOUT)
                .responseTimeout(RESPONSE_TIMEOUT)
                .bodyString(gson.toJson(body), ContentType.APPLICATION_JSON)
                .execute()
                .discardContent();
    }

    private Integer getCurrentVectorSize() throws IOException {
        try {
            String resp = Request.get(url + "/collections/" + collection)
                    .connectTimeout(CONNECT_TIMEOUT)
                    .responseTimeout(RESPONSE_TIMEOUT)
                    .execute()
                    .returnContent()
                    .asString(StandardCharsets.UTF_8);

            JsonElement rootEl = JsonParser.parseString(resp);
            if (!rootEl.isJsonObject()) return null;
            JsonObject root = rootEl.getAsJsonObject();
            JsonObject result = root.has("result") && root.get("result").isJsonObject()
                    ? root.getAsJsonObject("result") : null;
            if (result == null) return null;
            JsonObject config = result.has("config") && result.get("config").isJsonObject()
                    ? result.getAsJsonObject("config") : null;
            if (config == null) return null;
            JsonObject params = config.has("params") && config.get("params").isJsonObject()
                    ? config.getAsJsonObject("params") : null;
            if (params == null) return null;

            JsonElement vectorsEl = params.get("vectors");
            if (vectorsEl == null) return null;

            if (vectorsEl.isJsonObject()) {
                JsonObject v = vectorsEl.getAsJsonObject();
                if (v.has("size")) return v.get("size").getAsInt();
                for (Map.Entry<String, JsonElement> e : v.entrySet()) {
                    JsonObject cfg = e.getValue().getAsJsonObject();
                    if (cfg.has("size")) return cfg.get("size").getAsInt();
                }
            }
            return null;
        } catch (HttpResponseException e) {
            if (e.getStatusCode() == 404) return null;
            throw e;
        }
    }

    public void dropCollection() throws IOException {
        Request.delete(url + "/collections/" + collection)
                .connectTimeout(CONNECT_TIMEOUT)
                .responseTimeout(RESPONSE_TIMEOUT)
                .execute()
                .discardContent();
    }

    private String normalizeId(String rawId) {
        return java.util.UUID.nameUUIDFromBytes(rawId.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** Upsert single point; accepts object or primitive response; ?wait=true. */
    public void upsertPolicy(String id, String title, String text, float[] embedding) throws IOException {
        String uuid = normalizeId(id);

        Map<String, Object> point = new HashMap<>();
        point.put("id", uuid);
        point.put("vector", embedding);
        point.put("payload", Map.of("title", title, "text", text));

        Map<String, Object> reqBody = Map.of("points", List.of(point));
        String endpoint = url + "/collections/" + collection + "/points?wait=true";

        String resp = Request.put(endpoint)
                .connectTimeout(CONNECT_TIMEOUT)
                .responseTimeout(RESPONSE_TIMEOUT)
                .bodyString(gson.toJson(reqBody), ContentType.APPLICATION_JSON)
                .execute()
                .handleResponse(httpResp -> {
                    int code = httpResp.getCode();
                    String body = httpResp.getEntity() != null
                            ? EntityUtils.toString(httpResp.getEntity(), StandardCharsets.UTF_8)
                            : "";
                    if (code >= 200 && code < 300) return body;
                    throw new HttpResponseException(code, body.isBlank() ? "Bad Request (empty body)" : body);
                });

        // Be lenient: body may be a primitive (e.g., true). Only inspect errors if it's an object.
        try {
            JsonElement el = JsonParser.parseString(resp);
            if (el.isJsonObject()) {
                JsonObject jo = el.getAsJsonObject();
                if (jo.has("status") && jo.get("status").isJsonObject()) {
                    JsonObject status = jo.getAsJsonObject("status");
                    if (status.has("error") && !status.get("error").isJsonNull()) {
                        throw new IOException("Qdrant upsert logical error: " + resp);
                    }
                }
            }
        } catch (JsonSyntaxException ignore) {
            // Non-JSON or empty body; success already guaranteed by 2xx, so ignore.
        }

        System.out.println("[QdrantClient] Upserted policy point with ID: " + id + " (uuid: " + uuid + ")");
    }

    public List<PolicyMatch> search(float[] queryEmbedding, int topK) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("vector", queryEmbedding);
        body.put("limit", topK);
        body.put("with_payload", true);

        String response = Request.post(url + "/collections/" + collection + "/points/search")
                .connectTimeout(CONNECT_TIMEOUT)
                .responseTimeout(RESPONSE_TIMEOUT)
                .bodyString(gson.toJson(body), ContentType.APPLICATION_JSON)
                .execute()
                .returnContent()
                .asString(StandardCharsets.UTF_8);

        JsonElement parsedEl = JsonParser.parseString(response);
        List<PolicyMatch> matches = new ArrayList<>();
        if (!parsedEl.isJsonObject()) return matches;

        JsonObject parsed = parsedEl.getAsJsonObject();
        if (!parsed.has("result") || !parsed.get("result").isJsonArray()) return matches;

        JsonArray res = parsed.getAsJsonArray("result");
        for (JsonElement el : res) {
            JsonObject hit = el.getAsJsonObject();
            JsonObject payload = hit.has("payload") && hit.get("payload").isJsonObject()
                    ? hit.getAsJsonObject("payload") : null;
            if (payload == null) continue;

            String title = payload.has("title") ? payload.get("title").getAsString() : "<no title>";
            String text  = payload.has("text")  ? payload.get("text").getAsString()  : "<no text>";
            float score  = hit.has("score") ? hit.get("score").getAsFloat() : 0f;

            matches.add(new PolicyMatch(title, text, score));
        }
        return matches;
    }

    public long countPoints() throws IOException {
        String resp = Request.post(url + "/collections/" + collection + "/points/count")
                .connectTimeout(CONNECT_TIMEOUT)
                .responseTimeout(RESPONSE_TIMEOUT)
                .bodyString("{\"exact\":true}", ContentType.APPLICATION_JSON)
                .execute()
                .returnContent()
                .asString(StandardCharsets.UTF_8);
        JsonElement el = JsonParser.parseString(resp);
        if (!el.isJsonObject()) return 0L;
        JsonObject jo = el.getAsJsonObject();
        return jo.has("result") && jo.get("result").isJsonObject()
                && jo.getAsJsonObject("result").has("count")
                ? jo.getAsJsonObject("result").get("count").getAsLong()
                : 0L;
    }

    public static class PolicyMatch {
        public final String title;
        public final String text;
        public final float score;
        public PolicyMatch(String title, String text, float score) { this.title = title; this.text = text; this.score = score; }
        @Override public String toString() { return "→ " + title + " (score " + score + ")\n" + text; }
    }
}