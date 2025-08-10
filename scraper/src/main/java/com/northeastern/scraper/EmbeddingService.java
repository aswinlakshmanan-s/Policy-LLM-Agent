package com.northeastern.scraper;

import ai.djl.ModelException;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;
import ai.djl.inference.Predictor;
import ai.djl.training.util.ProgressBar;

import java.io.IOException;

/** DJL-based embeddings using sentence-transformers/all-MiniLM-L6-v2 (384 dims). */
public class EmbeddingService {
    private final Predictor<String, float[]> predictor;

    // NOTE: no 'throws' on the constructor; we wrap checked exceptions
    public EmbeddingService(String ignored) {
        try {
            Criteria<String, float[]> criteria = Criteria.builder()
                    .setTypes(String.class, float[].class)
                    .optModelUrls("djl://ai.djl.huggingface.pytorch/sentence-transformers/all-MiniLM-L6-v2")
                    .optEngine("PyTorch")
                    .optProgress(new ProgressBar())
                    .build();
            ZooModel<String, float[]> model = criteria.loadModel();
            this.predictor = model.newPredictor();
        } catch (IOException | ModelException e) {
            throw new RuntimeException("Failed to initialize DJL embedder", e);
        }
    }

    // embed still throws TranslateException (callers already catch this in your code)
    public float[] embed(String text) throws TranslateException {
        return predictor.predict(text);
    }
}
