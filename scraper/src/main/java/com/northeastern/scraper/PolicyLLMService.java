package com.northeastern.scraper;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.receptionist.Receptionist;
import akka.actor.typed.receptionist.ServiceKey;
import akka.cluster.typed.Cluster;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.*;
import org.apache.hc.client5.http.fluent.Request;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.util.Timeout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * LLM service actor that processes policy questions using AI generation.
 * UPDATED: Generates longer, more detailed responses
 */
public class PolicyLLMService extends AbstractBehavior<PolicyLLMService.Command> {

    // Service key for cluster registration
    public static final ServiceKey<Command> POLICY_LLM_SERVICE_KEY =
            ServiceKey.create(Command.class, "policy-llm-service");

    // Command messages
    public interface Command extends PolicySerializable {}

    public static final class GenerateResponse implements Command {
        public final String requestId;
        public final String query;
        public final List<QdrantClient.PolicyMatch> searchResults;
        public final ActorRef<GenerationResponse> replyTo;

        @JsonCreator
        public GenerateResponse(
                @JsonProperty("requestId") String requestId,
                @JsonProperty("query") String query,
                @JsonProperty("searchResults") List<QdrantClient.PolicyMatch> searchResults,
                @JsonProperty("replyTo") ActorRef<GenerationResponse> replyTo
        ) {
            this.requestId = requestId;
            this.query = query;
            this.searchResults = searchResults;
            this.replyTo = replyTo;
        }
    }

    public static final class GenerationResponse implements PolicySerializable {
        public final String requestId;
        public final String answer;
        public final String processedBy;
        public final boolean success;
        public final long processingTimeMs;

        @JsonCreator
        public GenerationResponse(
                @JsonProperty("requestId") String requestId,
                @JsonProperty("answer") String answer,
                @JsonProperty("processedBy") String processedBy,
                @JsonProperty("success") boolean success,
                @JsonProperty("processingTimeMs") long processingTimeMs
        ) {
            this.requestId = requestId;
            this.answer = answer;
            this.processedBy = processedBy;
            this.success = success;
            this.processingTimeMs = processingTimeMs;
        }
    }

    private static final class GenerationComplete implements Command {
        public final GenerateResponse originalRequest;
        public final String answer;
        public final boolean success;
        public final long processingTime;

        public GenerationComplete(GenerateResponse originalRequest, String answer, boolean success, long processingTime) {
            this.originalRequest = originalRequest;
            this.answer = answer;
            this.success = success;
            this.processingTime = processingTime;
        }
    }

    // UPDATED: Enhanced Ollama settings for longer responses
    private static final String OLLAMA_MODEL = "phi3:mini";
    private static final String OLLAMA_URL = "http://localhost:11434/api/generate";
    private static final Gson GSON = new GsonBuilder().create();

    private final String nodeAddress;
    private final boolean ollamaAvailable;

    public static Behavior<Command> create() {
        return Behaviors.setup(PolicyLLMService::new);
    }

    private PolicyLLMService(ActorContext<Command> context) {
        super(context);

        Cluster cluster = Cluster.get(context.getSystem());
        this.nodeAddress = cluster.selfMember().address().toString();

        // REGISTER with cluster receptionist
        context.getSystem().receptionist().tell(
                Receptionist.register(POLICY_LLM_SERVICE_KEY, context.getSelf())
        );

        this.ollamaAvailable = checkOllamaAvailability();

        System.out.println("🧠 Policy LLM service registered on " + nodeAddress);
        System.out.println("🤖 AI Generation: " + (ollamaAvailable ? "Phi-3 Available (Enhanced Mode)" : "Fallback Mode"));
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(GenerateResponse.class, this::onGenerateResponse)
                .onMessage(GenerationComplete.class, this::onGenerationComplete)
                .build();
    }

    private Behavior<Command> onGenerateResponse(GenerateResponse request) {
        System.out.println(String.format(
                "🧠 Generating detailed policy response '%s': %s (with %d search results)",
                request.requestId,
                request.query,
                request.searchResults.size()
        ));

        long startTime = System.currentTimeMillis();

        if (ollamaAvailable) {
            // Process with real LLM - enhanced for longer responses
            CompletableFuture.supplyAsync(() -> {
                try {
                    String answer = generateDetailedWithOllama(request.query, request.searchResults);
                    long processingTime = System.currentTimeMillis() - startTime;
                    return new GenerationComplete(request, answer, true, processingTime);

                } catch (Exception e) {
                    System.err.println("[LLM] Generation failed: " + e.getMessage());
                    String fallback = generateEnhancedFallbackResponse(request.query, request.searchResults);
                    long processingTime = System.currentTimeMillis() - startTime;
                    return new GenerationComplete(request, fallback, false, processingTime);
                }
            }).thenAccept(result -> getContext().getSelf().tell(result));

        } else {
            // Enhanced fallback mode
            String fallback = generateEnhancedFallbackResponse(request.query, request.searchResults);
            long processingTime = System.currentTimeMillis() - startTime;
            getContext().getSelf().tell(new GenerationComplete(request, fallback, false, processingTime));
        }

        return this;
    }

    private Behavior<Command> onGenerationComplete(GenerationComplete complete) {
        GenerateResponse originalRequest = complete.originalRequest;

        GenerationResponse response = new GenerationResponse(
                originalRequest.requestId,
                complete.answer,
                nodeAddress,
                complete.success,
                complete.processingTime
        );

        System.out.println(String.format(
                "✅ Detailed policy response completed '%s' in %dms: %s",
                originalRequest.requestId,
                complete.processingTime,
                complete.success ? "AI Generated" : "Enhanced Fallback"
        ));

        // DEMONSTRATE: tell pattern - send response back
        originalRequest.replyTo.tell(response);

        return this;
    }

    // UPDATED: Enhanced Ollama generation with longer, detailed responses
    private String generateDetailedWithOllama(String query, List<QdrantClient.PolicyMatch> searchResults) throws IOException {
        System.out.println("[LLM] 🧠 Generating detailed response with Phi-3...");

        String prompt = buildEnhancedPolicyPrompt(query, searchResults);

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", OLLAMA_MODEL);
        requestBody.addProperty("prompt", prompt);
        requestBody.addProperty("stream", false);

        // UPDATED: Enhanced parameters for longer, better responses
        JsonObject options = new JsonObject();
        options.addProperty("temperature", 0.3);        // More creative than 0.1
        options.addProperty("top_p", 0.9);             // Better token selection
        options.addProperty("num_predict", 600);       // INCREASED: Much longer responses
        options.addProperty("num_ctx", 4096);          // INCREASED: More context
        options.addProperty("repeat_penalty", 1.1);     // Avoid repetition
        options.addProperty("top_k", 40);              // Better diversity
        requestBody.add("options", options);

        String response = Request.post(OLLAMA_URL)
                .connectTimeout(Timeout.ofSeconds(15))      // Longer timeout for detailed responses
                .responseTimeout(Timeout.ofSeconds(45))     // Much longer response timeout
                .addHeader("Content-Type", "application/json")
                .bodyString(GSON.toJson(requestBody), ContentType.APPLICATION_JSON)
                .execute()
                .returnContent()
                .asString(StandardCharsets.UTF_8);

        JsonObject responseObj = JsonParser.parseString(response).getAsJsonObject();

        if (responseObj.has("response")) {
            String generatedText = responseObj.get("response").getAsString().trim();
            return enhanceResponse(generatedText, query, searchResults);
        } else {
            throw new IOException("Ollama generation failed");
        }
    }

    // UPDATED: Enhanced fallback with much more detail
    private String generateEnhancedFallbackResponse(String query, List<QdrantClient.PolicyMatch> searchResults) {
        System.out.println("[LLM] 🔄 Using enhanced detailed fallback generation");

        if (searchResults.isEmpty()) {
            return String.format(
                    "## No Specific Policies Found\n\n" +
                            "I couldn't find specific policies about \"%s\" in the Northeastern University policy database.\n\n" +
                            "**Processed by:** %s\n\n" +
                            "**Suggestions for finding relevant information:**\n" +
                            "• Try broader search terms (e.g., 'resource management' instead of 'fair usage')\n" +
                            "• Search for related topics like 'facilities', 'equipment', or 'allocation'\n" +
                            "• Contact the relevant department directly:\n" +
                            "  - IT Services for technology resources\n" +
                            "  - Facilities Management for physical resources\n" +
                            "  - Research Administration for research resources\n" +
                            "• Check the complete policy catalog online\n\n" +
                            "**Common resource-related policies include:**\n" +
                            "• IT Acceptable Use Policies\n" +
                            "• Research Facility Usage Guidelines\n" +
                            "• Equipment and Space Allocation Procedures\n" +
                            "• Shared Resource Management Policies",
                    query, nodeAddress
            );
        }

        StringBuilder response = new StringBuilder();
        response.append("## Comprehensive Policy Analysis\n");
        response.append("**Query:** \"").append(query).append("\"\n");
        response.append("**Processed by:** ").append(nodeAddress).append("\n");
        response.append("**Analysis Date:** ").append(java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n\n");

        response.append("### Relevant Policy Information\n\n");
        response.append("Based on my analysis of Northeastern University policies, here's comprehensive information about \"")
                .append(query).append("\":\n\n");

        for (int i = 0; i < searchResults.size(); i++) {
            QdrantClient.PolicyMatch match = searchResults.get(i);

            response.append("#### ").append(i + 1).append(". ").append(match.title).append("\n");
            response.append("**Relevance Score:** ").append(String.format("%.1f%%", match.score * 100)).append("\n\n");

            if (match.text != null && !match.text.trim().isEmpty()) {
                // Extract multiple relevant sections
                String detailedExcerpt = extractDetailedContent(match.text, query);
                response.append("**Policy Details:**\n").append(detailedExcerpt).append("\n");

                // Add interpretation
                String interpretation = interpretPolicyContent(match.title, match.text, query);
                response.append("**What This Means:** ").append(interpretation).append("\n\n");
            }

            if (i < searchResults.size() - 1) {
                response.append("---\n\n");
            }
        }

        // Add comprehensive guidance
        response.append("### Practical Guidance\n\n");
        response.append(generatePracticalGuidance(query, searchResults));

        response.append("\n### Next Steps\n\n");
        response.append(generateDetailedNextSteps(query, searchResults));

        response.append("\n### Additional Resources\n\n");
        response.append("For the most up-to-date and complete policy information:\n");
        response.append("• Visit the official Northeastern University policy website\n");
        response.append("• Contact the relevant department offices directly\n");
        response.append("• Consult with academic advisors or administrative staff\n");
        response.append("• Review the current student handbook or employee manual\n\n");

        response.append("*This analysis was generated by the distributed policy processing system and ");
        response.append("represents the best available information from the indexed policy database.*");

        return response.toString();
    }

    // UPDATED: Enhanced prompt for longer, detailed responses
    private String buildEnhancedPolicyPrompt(String query, List<QdrantClient.PolicyMatch> searchResults) {
        String policyContext = searchResults.stream()
                .limit(3) // Use top 3 results for more context
                .map(match -> String.format("=== Policy: %s ===\nContent: %s\n=== End Policy ===",
                        match.title,
                        match.text.length() > 800 ? match.text.substring(0, 800) + "..." : match.text))
                .collect(Collectors.joining("\n\n"));

        return String.format(
                "You are a knowledgeable assistant specializing in Northeastern University policies. " +
                        "Provide a comprehensive, detailed answer based on the policy information below.\n\n" +
                        "Question: %s\n\n" +
                        "Relevant Policies:\n%s\n\n" +
                        "Instructions for your response:\n" +
                        "- Provide a detailed explanation (4-6 paragraphs)\n" +
                        "- Quote specific policy sections with citations (e.g., 'According to Policy 507...')\n" +
                        "- Explain the practical implications for students, faculty, or staff\n" +
                        "- Include any relevant procedures, requirements, or deadlines\n" +
                        "- Mention related policies or cross-references when applicable\n" +
                        "- Provide actionable guidance and next steps\n" +
                        "- If the policies don't fully address the question, clearly state what's covered and what might need clarification\n" +
                        "- Use clear, professional language appropriate for university communications\n\n" +
                        "Please provide a thorough, helpful response:",
                query, policyContext
        );
    }

    // NEW: Enhance the AI response with additional context
    private String enhanceResponse(String aiResponse, String query, List<QdrantClient.PolicyMatch> searchResults) {
        StringBuilder enhanced = new StringBuilder();

        // Add header with context
        enhanced.append("## Northeastern University Policy Analysis\n");
        enhanced.append("**Query:** ").append(query).append("\n");
        enhanced.append("**AI-Generated Response with Policy Context**\n\n");

        // Main AI response
        enhanced.append("### Detailed Policy Information\n\n");
        enhanced.append(aiResponse);

        // Add policy references
        enhanced.append("\n\n### Referenced Policies\n\n");
        for (int i = 0; i < searchResults.size(); i++) {
            QdrantClient.PolicyMatch match = searchResults.get(i);
            enhanced.append("**").append(i + 1).append(". ").append(match.title).append("**\n");
            enhanced.append("Relevance: ").append(String.format("%.1f%%", match.score * 100)).append("\n");

            if (match.text != null && match.text.length() > 100) {
                String preview = match.text.substring(0, Math.min(200, match.text.length()));
                if (match.text.length() > 200) preview += "...";
                enhanced.append("Preview: ").append(preview).append("\n\n");
            }
        }

        // Add footer with guidance
        enhanced.append("### For More Information\n\n");
        enhanced.append("If you need additional clarification or have specific questions about these policies:\n");
        enhanced.append("• Contact the relevant university department\n");
        enhanced.append("• Consult with academic or administrative advisors\n");
        enhanced.append("• Review the complete policy documents online\n");
        enhanced.append("• Reach out to the appropriate office for official interpretations\n\n");
        enhanced.append("*Generated by: ").append(nodeAddress).append("*");

        return enhanced.toString();
    }

    // NEW: Extract more detailed content from policies
    private String extractDetailedContent(String text, String query) {
        if (text == null || text.trim().isEmpty()) {
            return "• Policy content available in full documentation";
        }

        String[] sentences = text.split("(?<=[.!?])\\s+");
        String[] questionWords = query.toLowerCase().split("\\s+");

        StringBuilder detailedContent = new StringBuilder();
        int relevantSentences = 0;

        // Find multiple relevant sentences for detailed content
        for (String sentence : sentences) {
            if (relevantSentences >= 5) break; // Get more sentences for detail

            String lowerSentence = sentence.toLowerCase();
            boolean isRelevant = false;

            // Check for question keyword relevance
            for (String word : questionWords) {
                if (word.length() > 2 && lowerSentence.contains(word)) {
                    isRelevant = true;
                    break;
                }
            }

            // Also include policy-relevant terms
            if (!isRelevant) {
                String[] policyTerms = {"must", "shall", "required", "policy", "procedure", "eligible",
                        "application", "process", "guidelines", "requirements", "standards"};
                for (String term : policyTerms) {
                    if (lowerSentence.contains(term) && sentence.length() > 30) {
                        isRelevant = true;
                        break;
                    }
                }
            }

            if (isRelevant && sentence.trim().length() > 20) {
                detailedContent.append("• ").append(sentence.trim()).append("\n");
                relevantSentences++;
            }
        }

        // If no relevant sentences, include first few meaningful sentences
        if (detailedContent.length() == 0) {
            for (int i = 0; i < Math.min(3, sentences.length); i++) {
                if (sentences[i].trim().length() > 30) {
                    detailedContent.append("• ").append(sentences[i].trim()).append("\n");
                }
            }
        }

        return detailedContent.toString();
    }

    // NEW: Interpret policy content for the user
    private String interpretPolicyContent(String policyTitle, String policyText, String query) {
        String lowerTitle = policyTitle.toLowerCase();
        String lowerQuery = query.toLowerCase();
        String lowerText = policyText.toLowerCase();

        if (lowerQuery.contains("resource") || lowerQuery.contains("usage") || lowerQuery.contains("allocation")) {
            if (lowerTitle.contains("research") || lowerText.contains("research")) {
                return "This policy governs how research resources should be allocated and used fairly across the university community.";
            } else if (lowerTitle.contains("facility") || lowerText.contains("facility")) {
                return "This policy outlines guidelines for equitable access to and usage of university facilities and equipment.";
            } else if (lowerTitle.contains("it") || lowerText.contains("technology")) {
                return "This policy addresses appropriate and fair use of information technology resources.";
            }
        }

        if (lowerTitle.contains("employment") && lowerQuery.contains("student")) {
            return "This policy explains student employment opportunities and fair hiring practices.";
        } else if (lowerTitle.contains("housing") && (lowerQuery.contains("housing") || lowerQuery.contains("residence"))) {
            return "This policy covers housing allocation procedures and residential community standards.";
        } else if (lowerTitle.contains("benefit") || lowerTitle.contains("insurance")) {
            return "This policy details benefit eligibility and fair access to university insurance programs.";
        }

        return "This policy provides important guidelines and procedures relevant to your inquiry.";
    }

    // NEW: Generate practical guidance based on query type
    private String generatePracticalGuidance(String query, List<QdrantClient.PolicyMatch> searchResults) {
        String lowerQuery = query.toLowerCase();

        StringBuilder guidance = new StringBuilder();

        if (lowerQuery.contains("resource") || lowerQuery.contains("usage")) {
            guidance.append("**For Resource Usage Questions:**\n");
            guidance.append("• Review the specific allocation procedures outlined in the relevant policies\n");
            guidance.append("• Understand any approval processes required before resource usage\n");
            guidance.append("• Be aware of any usage limitations, time restrictions, or priority systems\n");
            guidance.append("• Know who to contact for permission or conflict resolution\n");
            guidance.append("• Follow proper documentation and reporting requirements\n\n");
        }

        if (lowerQuery.contains("employment") || lowerQuery.contains("work")) {
            guidance.append("**For Employment-Related Questions:**\n");
            guidance.append("• Understand eligibility requirements and application processes\n");
            guidance.append("• Be aware of work-study regulations and hour limitations\n");
            guidance.append("• Know your rights and responsibilities as a student employee\n");
            guidance.append("• Understand the grievance and conflict resolution procedures\n\n");
        }

        if (lowerQuery.contains("housing") || lowerQuery.contains("residence")) {
            guidance.append("**For Housing-Related Questions:**\n");
            guidance.append("• Review application deadlines and assignment procedures\n");
            guidance.append("• Understand community standards and behavioral expectations\n");
            guidance.append("• Know the process for room changes or conflict resolution\n");
            guidance.append("• Be aware of any special housing programs or accommodations\n\n");
        }

        if (guidance.length() == 0) {
            guidance.append("**General Guidance:**\n");
            guidance.append("• Review all relevant policy details carefully\n");
            guidance.append("• Contact appropriate university offices for clarification\n");
            guidance.append("• Keep documentation of any applications or requests\n");
            guidance.append("• Be aware of deadlines and renewal requirements\n\n");
        }

        return guidance.toString();
    }

    // NEW: Generate detailed next steps
    private String generateDetailedNextSteps(String query, List<QdrantClient.PolicyMatch> searchResults) {
        StringBuilder steps = new StringBuilder();
        String lowerQuery = query.toLowerCase();

        if (lowerQuery.contains("resource") || lowerQuery.contains("usage")) {
            steps.append("**Immediate Actions:**\n");
            steps.append("1. Review the complete policy documents referenced above\n");
            steps.append("2. Identify the specific resources you need access to\n");
            steps.append("3. Contact the Resource Management Office or relevant department\n");
            steps.append("4. Complete any required application or approval processes\n");
            steps.append("5. Understand usage guidelines and reporting requirements\n\n");

            steps.append("**Key Contacts:**\n");
            steps.append("• IT Services: For technology resources and systems\n");
            steps.append("• Facilities Management: For physical spaces and equipment\n");
            steps.append("• Research Administration: For research-related resources\n");
            steps.append("• Department administrators: For department-specific resources\n\n");
        } else {
            steps.append("**Recommended Next Steps:**\n");
            steps.append("1. Review the complete text of relevant policies\n");
            steps.append("2. Contact the appropriate university office for specific guidance\n");
            steps.append("3. Consult with advisors or department administrators\n");
            steps.append("4. Keep documentation of any official communications\n");
            steps.append("5. Follow up if you need clarification on procedures\n\n");
        }

        steps.append("**For urgent questions:** Contact the main university information line or visit the appropriate office in person.\n");
        steps.append("**For policy interpretations:** Always seek official clarification from authorized university personnel.");

        return steps.toString();
    }

    private boolean checkOllamaAvailability() {
        try {
            String response = Request.get("http://localhost:11434/api/tags")
                    .connectTimeout(Timeout.ofSeconds(3))
                    .responseTimeout(Timeout.ofSeconds(5))
                    .execute()
                    .returnContent()
                    .asString();

            // Check if phi3 model is available
            JsonObject tags = JsonParser.parseString(response).getAsJsonObject();
            JsonArray models = tags.getAsJsonArray("models");

            for (int i = 0; i < models.size(); i++) {
                String modelName = models.get(i).getAsJsonObject().get("name").getAsString();
                if (modelName.startsWith("phi3")) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}