package com.northeastern.scraper;

import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class PolicyLoader {
    private final Map<String, String> policies = new HashMap<>();

    public PolicyLoader(String folderPath) throws Exception {
        File folder = new File(folderPath);
        for (File file : Objects.requireNonNull(folder.listFiles((dir, name) -> name.endsWith(".txt")))) {
            String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            policies.put(file.getName(), text);
        }
    }

    public Map<String, String> getPolicies() {
        return policies;
    }
}
