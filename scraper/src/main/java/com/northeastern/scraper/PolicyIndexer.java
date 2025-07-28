package com.northeastern.scraper;

import java.io.File;
import java.nio.charset.StandardCharsets;

public class PolicyIndexer {
    public static void main(String[] args) {
        try {
            String openAiKey = System.getenv("COHERE_API_KEY");
            if (openAiKey == null || openAiKey.isEmpty()) {
                throw new IllegalStateException("Missing COHERE_API_KEY environment variable");
            }
            EmbeddingService embeddingService = new EmbeddingService(openAiKey);
            QdrantClient qdrantClient = new QdrantClient("http://localhost:6333");

            File policyDir = new File("data/policies");
            File[] policyFiles = policyDir.listFiles((dir, name) -> name.endsWith(".txt"));
            if (policyFiles == null || policyFiles.length == 0)
                throw new RuntimeException("No policy files found!");

            // Create collection with vector size from first embedding
            String exampleText = readAll(policyFiles[0]);
            float[] exampleEmbedding = embeddingService.embed(exampleText);
            qdrantClient.createCollectionIfNotExists(exampleEmbedding.length);

            for (File file : policyFiles) {
                try {
                    String content = readAll(file);
                    String title = file.getName().replace(".txt", "");
                    float[] embedding = embeddingService.embed(content);
                    qdrantClient.upsertPolicy(file.getName(), title, content, embedding);
                    System.out.println("[PolicyIndexer] Indexed: " + file.getName());
                } catch (Exception e) {
                    System.err.println("[PolicyIndexer] Failed to index " + file.getName() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static String readAll(File file) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
