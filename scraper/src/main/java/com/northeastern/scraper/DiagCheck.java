package com.northeastern.scraper;

import java.nio.charset.StandardCharsets;
import org.apache.hc.client5.http.fluent.Request;
import org.apache.hc.core5.http.ContentType;

public class DiagCheck {
    public static void main(String[] args) {
        String query = (args.length > 0) ? args[0] : "leave of absence policy";
        System.out.println("== DIAG ==");
        System.out.println("Query: " + query);

        // ---- Embedding step ----
        try {
            var embedder = new EmbeddingService(null);
            long t0 = System.nanoTime();
            float[] v = embedder.embed(query);
            long t1 = System.nanoTime();
            System.out.println("[Embedding] OK dim=" + v.length + " time=" + (t1 - t0) / 1_000_000 + " ms");

            // ---- Qdrant step ----
            try {
                var qdrant = new QdrantClient("http://127.0.0.1:6333");
                long t2 = System.nanoTime();
                var hits = qdrant.search(v, 3);
                long t3 = System.nanoTime();
                System.out.println("[Qdrant] OK hits=" + hits.size() + " time=" + (t3 - t2) / 1_000_000 + " ms");
                for (var h : hits) System.out.println(" - " + h.title + " (score=" + h.score + ")");
            } catch (Exception qe) {
                System.out.println("[Qdrant] ERROR: " + qe.getClass().getName() + " -> " + qe.getMessage());
                qe.printStackTrace(System.out);
            }
        } catch (Throwable te) {
            System.out.println("[Embedding] ERROR: " + te.getClass().getName() + " -> " + te.getMessage());
            te.printStackTrace(System.out);
        }

        // ---- Ollama step ----
        String endpoint = System.getenv().getOrDefault("OLLAMA_ENDPOINT", "http://127.0.0.1:11434");
        String model    = System.getenv().getOrDefault("OLLAMA_GEN_MODEL", "qwen2:0.5b");
        try {
            System.out.println("[Ollama] Preflight " + endpoint + "/api/tags");
            Request.get(endpoint + "/api/tags").execute().discardContent();

            String body = "{\"model\":\"" + model + "\",\"prompt\":\"hi\",\"stream\":false}";
            long t4 = System.nanoTime();
            String resp = Request.post(endpoint + "/api/generate")
                    .addHeader("Content-Type", "application/json")
                    .bodyString(body, ContentType.APPLICATION_JSON)
                    .execute()
                    .returnContent()
                    .asString(StandardCharsets.UTF_8);
            long t5 = System.nanoTime();
            System.out.println("[Ollama] OK time=" + (t5 - t4) / 1_000_000 + " ms");
            System.out.println(resp);
        } catch (Throwable le) {
            System.out.println("[Ollama] ERROR: " + le.getClass().getName() + " -> " + le.getMessage());
            le.printStackTrace(System.out);
            System.out.println("Tip: run `ollama serve` and `ollama pull qwen2:0.5b` in another terminal.");
        }

        System.out.println("== DIAG DONE ==");
    }
}
