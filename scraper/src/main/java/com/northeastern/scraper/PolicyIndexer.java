package com.northeastern.scraper;

import ai.djl.translate.TranslateException;
import java.io.File;
import java.nio.charset.StandardCharsets;

public class PolicyIndexer {
    public static void main(String[] args) {
        try {
            EmbeddingService embeddingService = new EmbeddingService(null); // DJL embedder
            QdrantClient qdrantClient = new QdrantClient("http://localhost:6333");

            File policyDir = new File("data/policies");
            File[] policyFiles = policyDir.listFiles((dir, name) -> name.endsWith(".txt"));
            if (policyFiles == null || policyFiles.length == 0)
                throw new RuntimeException("No policy files found in data/policies!");

            // Create collection with correct vector size
            String exampleText = readAll(policyFiles[0]);
            float[] exampleEmbedding = embeddingService.embed(exampleText);
            qdrantClient.createCollectionIfNotExists(exampleEmbedding.length); // expect 384

            for (File file : policyFiles) {
                try {
                    String content = readAll(file);
                    String title = file.getName().replace(".txt", "");
                    float[] embedding = embeddingService.embed(content);
                    qdrantClient.upsertPolicy(file.getName(), title, content, embedding);
                    System.out.println("[PolicyIndexer] Indexed: " + file.getName());
                } catch (TranslateException te) {
                    System.err.println("[PolicyIndexer] Embed failed for " + file.getName() + ": " + te.getMessage());
                } catch (Exception e) {
                    System.err.println("[PolicyIndexer] Failed to index " + file.getName() + ": " + e.getMessage());
                }
            }

            System.out.println("[PolicyIndexer] Done. Count = " + qdrantClient.countPoints());

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static String readAll(File file) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
