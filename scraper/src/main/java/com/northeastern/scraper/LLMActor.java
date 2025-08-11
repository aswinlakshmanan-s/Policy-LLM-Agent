package com.northeastern.scraper;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.google.gson.*;
import org.apache.hc.client5.http.fluent.Request;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.util.Timeout;

import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class LLMActor extends AbstractBehavior<LLMActor.LLMMsg> {

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

    // FINALIZED CONFIGURATION - Phi-3 Mini (Optimized)
    private static final String OLLAMA_MODEL = "phi3:mini";
    private static final String OLLAMA_URL = "http://localhost:11434/api/generate";
    private static final Gson GSON = new GsonBuilder().create();
    private boolean ollamaAvailable = false;

    public static Behavior<LLMMsg> create() {
        return Behaviors.setup(LLMActor::new);
    }

    private LLMActor(ActorContext<LLMMsg> ctx) {
        super(ctx);
        System.out.println("[LLM] ✅ FINAL Policy Generator with Phi-3 Mini");
        System.out.println("[LLM] Model: " + OLLAMA_MODEL + " (Fast & Efficient)");

        // Check if Ollama is available
        this.ollamaAvailable = checkOllamaSetup();
    }

    @Override
    public Receive<LLMMsg> createReceive() {
        return newReceiveBuilder()
                .onMessage(Summarize.class, this::onSummarize)
                .build();
    }

    private Behavior<LLMMsg> onSummarize(Summarize msg) {
        System.out.println("[LLM] 🚀 Processing: " + msg.question);

        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        if (ollamaAvailable) {
                            return generateWithPhi3(msg.question, msg.results);
                        } else {
                            System.out.println("[LLM] Ollama not available, using enhanced fallback");
                            return generateEnhancedFallback(msg.question, msg.results);
                        }
                    } catch (Exception e) {
                        System.err.println("[LLM] Generation error: " + e.getMessage());
                        return generateEnhancedFallback(msg.question, msg.results);
                    }
                })
                .whenComplete((answer, throwable) -> {
                    if (throwable != null) {
                        System.err.println("[LLM] Async error: " + throwable.getMessage());
                        String fallback = generateEnhancedFallback(msg.question, msg.results);
                        msg.replyTo.tell(new LLMResponse(fallback));
                    } else {
                        System.out.println("[LLM] ✅ Response generated successfully");
                        msg.replyTo.tell(new LLMResponse(answer));
                    }
                });

        return this;
    }

    // PHI-3 MINI GENERATION (Optimized for policy responses)
    private String generateWithPhi3(String question, List<QdrantClient.PolicyMatch> results) throws IOException {
        System.out.println("[LLM] 🧠 Generating with Phi-3 Mini...");

        String prompt = buildPolicyPrompt(question, results);

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", OLLAMA_MODEL);
        requestBody.addProperty("prompt", prompt);
        requestBody.addProperty("stream", false);

        // Optimized parameters for policy responses
        JsonObject options = new JsonObject();
        options.addProperty("temperature", 0.1);        // Low for consistent, factual responses
        options.addProperty("top_p", 0.9);             // Focus on high-quality tokens
        options.addProperty("num_predict", 400);       // Good length for policy explanations
        options.addProperty("num_ctx", 3072);          // Sufficient context for policies
        options.addProperty("repeat_penalty", 1.15);   // Avoid repetition
        requestBody.add("options", options);

        long startTime = System.currentTimeMillis();

        String response = Request.post(OLLAMA_URL)
                .connectTimeout(Timeout.ofSeconds(10))
                .responseTimeout(Timeout.ofSeconds(45))
                .addHeader("Content-Type", "application/json")
                .bodyString(GSON.toJson(requestBody), ContentType.APPLICATION_JSON)
                .execute()
                .returnContent()
                .asString(StandardCharsets.UTF_8);

        long generationTime = System.currentTimeMillis() - startTime;
        System.out.println("[LLM] ⚡ Phi-3 generated response in " + generationTime + "ms");

        JsonObject responseObj = JsonParser.parseString(response).getAsJsonObject();

        if (responseObj.has("response")) {
            String generatedText = responseObj.get("response").getAsString().trim();
            return cleanupPolicyResponse(generatedText);
        } else if (responseObj.has("error")) {
            throw new IOException("Phi-3 error: " + responseObj.get("error").getAsString());
        } else {
            throw new IOException("Unexpected Phi-3 response format");
        }
    }

    // ENHANCED FALLBACK (Better than basic fallback)
    private String generateEnhancedFallback(String question, List<QdrantClient.PolicyMatch> results) {
        System.out.println("[LLM] 🔄 Using enhanced intelligent fallback");

        if (results.isEmpty()) {
            return generateNoResultsAdvice(question);
        }

        StringBuilder response = new StringBuilder();

        // Smart introduction based on question type
        String questionType = analyzeQuestionType(question);
        response.append("Based on Northeastern University policies, here's information about ")
                .append(questionType).append(":\n\n");

        // Process top policy matches with intelligent extraction
        for (int i = 0; i < Math.min(results.size(), 3); i++) {
            QdrantClient.PolicyMatch match = results.get(i);

            response.append("## ").append(match.title).append("\n");
            response.append("*Relevance: ").append(String.format("%.1f%%", match.score * 100)).append("*\n\n");

            String smartExcerpt = extractSmartPolicyContent(match.text, question);
            response.append(smartExcerpt).append("\n");

            String guidance = generatePolicyGuidance(match.title, question);
            if (!guidance.isEmpty()) {
                response.append("**What this means:** ").append(guidance).append("\n");
            }

            response.append("\n---\n\n");
        }

        // Add smart next steps
        response.append(generateSmartNextSteps(questionType, results));

        return response.toString();
    }

    private String buildPolicyPrompt(String question, List<QdrantClient.PolicyMatch> results) {
        String policyContext = results.stream()
                .limit(3)
                .map(match -> String.format("Policy: %s\nContent: %s",
                        match.title,
                        match.text.length() > 600 ? match.text.substring(0, 600) + "..." : match.text))
                .collect(Collectors.joining("\n\n"));

        return String.format(
                "You are a helpful assistant for Northeastern University policies. " +
                        "Provide a clear, accurate answer based on the policy information below.\n\n" +
                        "Question: %s\n\n" +
                        "Relevant Policies:\n%s\n\n" +
                        "Instructions:\n" +
                        "- Give a direct, helpful answer\n" +
                        "- Quote specific policy details when relevant\n" +
                        "- If policies don't fully address the question, say so\n" +
                        "- Keep response under 300 words\n" +
                        "- Use clear, professional language\n\n" +
                        "Answer:",
                question, policyContext
        );
    }

    private String cleanupPolicyResponse(String response) {
        return response
                .replaceAll("(?i)^answer:\\s*", "")
                .replaceAll("(?i)based on the.*?policies[,:]?\\s*", "")
                .replaceAll("\n{3,}", "\n\n")
                .replaceAll("\\*{2,}", "**")
                .trim();
    }

    private String analyzeQuestionType(String question) {
        String lowerQ = question.toLowerCase();

        if (lowerQ.contains("transfer") && (lowerQ.contains("student") || lowerQ.contains("credit"))) {
            return "transfer student policies and credit transfer";
        } else if (lowerQ.contains("housing") || lowerQ.contains("residence") || lowerQ.contains("dorm")) {
            return "housing and residential policies";
        } else if (lowerQ.contains("insurance") || lowerQ.contains("benefit") || lowerQ.contains("health")) {
            return "insurance and benefit policies";
        } else if (lowerQ.contains("employment") || lowerQ.contains("work") || lowerQ.contains("job")) {
            return "employment policies";
        } else if (lowerQ.contains("academic") || lowerQ.contains("grade") || lowerQ.contains("course")) {
            return "academic policies";
        } else {
            return "university policies related to your question";
        }
    }

    private String extractSmartPolicyContent(String text, String question) {
        if (text == null || text.trim().isEmpty()) {
            return "*Policy content not available in detail.*";
        }

        String[] questionWords = question.toLowerCase().split("\\s+");
        String[] sentences = text.split("(?<=[.!?])\\s+");

        StringBuilder relevantContent = new StringBuilder();
        int relevantCount = 0;

        // Find sentences most relevant to the question
        for (String sentence : sentences) {
            if (relevantCount >= 3) break;

            String lowerSentence = sentence.toLowerCase();
            int relevanceScore = 0;

            for (String word : questionWords) {
                if (word.length() > 2 && lowerSentence.contains(word)) {
                    relevanceScore++;
                }
            }

            if (relevanceScore > 0 || (relevantCount == 0 && sentence.trim().length() > 20)) {
                relevantContent.append("• ").append(sentence.trim()).append("\n");
                relevantCount++;
            }
        }

        if (relevantContent.length() == 0) {
            // Fallback to first meaningful sentence
            for (String sentence : sentences) {
                if (sentence.trim().length() > 30) {
                    relevantContent.append("• ").append(sentence.trim()).append("\n");
                    break;
                }
            }
        }

        return relevantContent.toString();
    }

    private String generatePolicyGuidance(String policyTitle, String question) {
        String lowerTitle = policyTitle.toLowerCase();
        String lowerQuestion = question.toLowerCase();

        if (lowerTitle.contains("employment") && lowerQuestion.contains("student")) {
            return "This policy outlines student employment opportunities and requirements.";
        } else if (lowerTitle.contains("housing") || lowerTitle.contains("residence")) {
            return "This covers housing rules, application processes, and residential life policies.";
        } else if (lowerTitle.contains("benefit") || lowerTitle.contains("insurance")) {
            return "This explains eligibility requirements and coverage details for university benefits.";
        } else if (lowerTitle.contains("transfer") || lowerTitle.contains("credit")) {
            return "This may contain information about credit evaluation and transfer procedures.";
        } else if (lowerTitle.contains("academic")) {
            return "This covers academic requirements, procedures, and standards.";
        }

        return "This policy may contain relevant information for your inquiry.";
    }

    private String generateSmartNextSteps(String questionType, List<QdrantClient.PolicyMatch> results) {
        StringBuilder steps = new StringBuilder();
        steps.append("## Next Steps\n\n");

        if (questionType.contains("transfer")) {
            steps.append("**For Transfer Students:**\n");
            steps.append("• Contact the Registrar's Office for official credit evaluation\n");
            steps.append("• Meet with academic advisors in your intended major\n");
            steps.append("• Review the undergraduate catalog for degree requirements\n\n");
        } else if (questionType.contains("housing")) {
            steps.append("**For Housing Information:**\n");
            steps.append("• Contact Residential Life: reslife@northeastern.edu\n");
            steps.append("• Check housing application deadlines and requirements\n");
            steps.append("• Review residence hall options and policies\n\n");
        } else if (questionType.contains("insurance")) {
            steps.append("**For Benefits Information:**\n");
            steps.append("• Contact Human Resources for detailed benefit explanations\n");
            steps.append("• Review enrollment deadlines and eligibility requirements\n");
            steps.append("• Compare plan options during open enrollment\n\n");
        } else {
            steps.append("**For More Information:**\n");
            steps.append("• Contact the relevant department directly\n");
            steps.append("• Review complete policy documents on the university website\n");
            steps.append("• Speak with academic or administrative advisors\n\n");
        }

        steps.append("**Need more specific help?** Try asking more detailed questions or contact university offices directly.");

        return steps.toString();
    }

    private String generateNoResultsAdvice(String question) {
        return String.format(
                "I couldn't find specific policies about \"%s\" in the current database.\n\n" +
                        "**Suggestions:**\n" +
                        "• Try rephrasing with different keywords\n" +
                        "• Search for broader topics (e.g., 'academic policies' instead of specific procedures)\n" +
                        "• Contact relevant university departments directly\n\n" +
                        "**Common policy areas:**\n" +
                        "• Academic policies and procedures\n" +
                        "• Housing and residential life\n" +
                        "• Student employment and work-study\n" +
                        "• Insurance and employee benefits\n" +
                        "• Student conduct and behavior\n\n" +
                        "**Direct contacts:**\n" +
                        "• Academic questions: Registrar's Office\n" +
                        "• Housing: Residential Life\n" +
                        "• Employment: Human Resources\n" +
                        "• General inquiries: Student Services",
                question
        );
    }

    private boolean checkOllamaSetup() {
        try {
            // Test connection
            String response = Request.get("http://localhost:11434/api/tags")
                    .connectTimeout(Timeout.ofSeconds(3))
                    .responseTimeout(Timeout.ofSeconds(5))
                    .execute()
                    .returnContent()
                    .asString();

            // Check if our model is available
            JsonObject tags = JsonParser.parseString(response).getAsJsonObject();
            JsonArray models = tags.getAsJsonArray("models");

            for (int i = 0; i < models.size(); i++) {
                String modelName = models.get(i).getAsJsonObject().get("name").getAsString();
                if (modelName.startsWith("phi3")) {
                    System.out.println("[LLM] ✅ Phi-3 model found and ready!");
                    return true;
                }
            }

            System.out.println("[LLM] ⚠️ Phi-3 not found. Run: ollama pull phi3:mini");
            System.out.println("[LLM] Will use enhanced fallback mode");
            return false;

        } catch (Exception e) {
            System.out.println("[LLM] ⚠️ Ollama not running. Start with: ollama serve");
            System.out.println("[LLM] Will use enhanced fallback mode");
            return false;
        }
    }
}