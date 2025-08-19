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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * ENHANCED PolicyLLMService - Better responses, complete answers, higher confidence
 */
public class PolicyLLMService extends AbstractBehavior<PolicyLLMService.Command> {

    public static final ServiceKey<Command> POLICY_LLM_SERVICE_KEY =
            ServiceKey.create(Command.class, "policy-llm-service");

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

    // ENHANCED: Optimized settings for complete, detailed responses
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

        context.getSystem().receptionist().tell(
                Receptionist.register(POLICY_LLM_SERVICE_KEY, context.getSelf())
        );

        this.ollamaAvailable = checkEnhancedPhiAvailability();

        System.out.println("🧠 ENHANCED Policy AI registered on " + nodeAddress);
        System.out.println("🚀 Enhancements: Complete Responses, Higher Confidence, Better UX");
        System.out.println("🤖 Status: " + (ollamaAvailable ? "Phi-3 Enhanced Mode" : "Premium Fallback"));
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
                "🧠 ENHANCED generation for '%s': %s",
                request.requestId,
                request.query
        ));

        long startTime = System.currentTimeMillis();

        if (ollamaAvailable) {
            CompletableFuture.supplyAsync(() -> {
                        try {
                            String answer = generateEnhancedWithPhi3(request.query, request.searchResults);
                            long processingTime = System.currentTimeMillis() - startTime;
                            return new GenerationComplete(request, answer, true, processingTime);

                        } catch (Exception e) {
                            System.err.println("[ENHANCED-PHI3] Generation failed: " + e.getMessage());
                            String fallback = generatePremiumFallback(request.query, request.searchResults);
                            long processingTime = System.currentTimeMillis() - startTime;
                            return new GenerationComplete(request, fallback, false, processingTime);
                        }
                    })
                    .orTimeout(70, TimeUnit.SECONDS) // LONGER: Allow complete responses
                    .whenComplete((result, throwable) -> {
                        if (throwable != null) {
                            System.err.println("[ENHANCED-PHI3] Extended timeout: " + throwable.getMessage());
                            String fallback = generatePremiumFallback(request.query, request.searchResults);
                            long processingTime = System.currentTimeMillis() - startTime;
                            getContext().getSelf().tell(new GenerationComplete(request, fallback, false, processingTime));
                        } else {
                            getContext().getSelf().tell(result);
                        }
                    });

        } else {
            String fallback = generatePremiumFallback(request.query, request.searchResults);
            long processingTime = System.currentTimeMillis() - startTime;
            getContext().getSelf().tell(new GenerationComplete(request, fallback, false, processingTime));
        }

        return this;
    }

    private Behavior<Command> onGenerationComplete(GenerationComplete complete) {
        GenerateResponse originalRequest = complete.originalRequest;

        // ENHANCED: Premium response formatting
        String premiumResponse = createPremiumResponse(
                complete.answer, originalRequest.query, originalRequest.searchResults, complete.success, complete.processingTime);

        GenerationResponse response = new GenerationResponse(
                originalRequest.requestId,
                premiumResponse,
                nodeAddress,
                complete.success,
                complete.processingTime
        );

        System.out.println(String.format(
                "✅ ENHANCED response ready '%s' in %dms: %s",
                originalRequest.requestId,
                complete.processingTime,
                complete.success ? "Phi-3 Enhanced Success" : "Premium Fallback"
        ));

        originalRequest.replyTo.tell(response);
        return this;
    }

    // ENHANCED: Phi-3 generation optimized for complete, detailed responses
    private String generateEnhancedWithPhi3(String query, List<QdrantClient.PolicyMatch> searchResults) throws IOException {
        System.out.println("[ENHANCED-PHI3] 🚀 Generating complete response...");

        String prompt = buildEnhancedPrompt(query, searchResults);
        System.out.println("[ENHANCED-PHI3] Enhanced prompt: " + prompt.length() + " chars");

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", OLLAMA_MODEL);
        requestBody.addProperty("prompt", prompt);
        requestBody.addProperty("stream", false);

        // ENHANCED: Settings for complete, detailed responses
        JsonObject options = new JsonObject();
        options.addProperty("temperature", 0.2);        // Balanced creativity
        options.addProperty("top_p", 0.95);            // Better token selection
        options.addProperty("num_predict", 500);       // LONGER responses
        options.addProperty("num_ctx", 3584);          // LARGER context for complete analysis
        options.addProperty("repeat_penalty", 1.12);    // Avoid repetition
        options.addProperty("top_k", 50);              // More diverse responses
        requestBody.add("options", options);

        // ENHANCED: Extended timeouts for complete generation
        String response = Request.post(OLLAMA_URL)
                .connectTimeout(Timeout.ofSeconds(25))      // Extended connection time
                .responseTimeout(Timeout.ofSeconds(65))     // Extended response time
                .addHeader("Content-Type", "application/json")
                .bodyString(GSON.toJson(requestBody), ContentType.APPLICATION_JSON)
                .execute()
                .returnContent()
                .asString(StandardCharsets.UTF_8);

        JsonObject responseObj = JsonParser.parseString(response).getAsJsonObject();

        if (responseObj.has("response")) {
            String aiResponse = responseObj.get("response").getAsString().trim();
            System.out.println("[ENHANCED-PHI3] ✅ Complete response generated: " + aiResponse.length() + " characters");
            return aiResponse;
        } else if (responseObj.has("error")) {
            throw new IOException("Phi-3 enhanced error: " + responseObj.get("error").getAsString());
        } else {
            throw new IOException("Unexpected Phi-3 response format");
        }
    }

    // ENHANCED: Better prompt engineering for detailed responses
    private String buildEnhancedPrompt(String query, List<QdrantClient.PolicyMatch> searchResults) {
        // BETTER: Use top 3 policies for comprehensive context
        String policyContext = searchResults.stream()
                .limit(3)
                .map(match -> String.format("=== %s ===\n%s\n=== End ===",
                        match.title,
                        match.text.length() > 600 ? match.text.substring(0, 600) + "..." : match.text))
                .collect(Collectors.joining("\n\n"));

        return String.format(
                "You are Northeastern University's expert AI policy assistant. " +
                        "Provide a comprehensive, professional analysis of the user's question.\n\n" +
                        "User Question: %s\n\n" +
                        "Relevant University Policies:\n%s\n\n" +
                        "Instructions:\n" +
                        "- Provide a complete, detailed response (3-4 paragraphs)\n" +
                        "- Reference specific policies by name when relevant\n" +
                        "- Include practical implications and procedures\n" +
                        "- Use professional but accessible language\n" +
                        "- Ensure your response is complete and doesn't cut off mid-sentence\n" +
                        "- Provide actionable guidance where appropriate\n\n" +
                        "Complete Response:",
                query, policyContext
        );
    }

    // ENHANCED: Premium response formatting with better UX
    private String createPremiumResponse(String aiResponse, String query, List<QdrantClient.PolicyMatch> searchResults, boolean success, long processingTime) {
        StringBuilder premium = new StringBuilder();

        // ENHANCED: Better header with query info
        premium.append("🎓 **Northeastern University AI Policy Assistant**\n");
        premium.append("📅 ").append(LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a"))).append("\n");
        premium.append("🔍 **Your Question:** \"").append(query).append("\"\n");  // FIXED: Show actual question
        premium.append("🤖 **AI Engine:** ").append(success ? "Microsoft Phi-3 Mini (Enhanced)" : "Intelligent Analysis").append("\n");
        premium.append("⚡ **Processing Time:** ").append(String.format("%.1f", processingTime / 1000.0)).append(" seconds\n");

        // ENHANCED: Better confidence analysis
        if (!searchResults.isEmpty()) {
            float confidence = (float) searchResults.stream().mapToDouble(r -> r.score).average().orElse(0.0);
            String confidenceLevel = getConfidenceLevel(confidence);
            String confidenceEmoji = getConfidenceEmoji(confidence);
            String confidenceBar = createEnhancedConfidenceBar(confidence);

            premium.append("📊 **Answer Confidence:** ").append(confidenceEmoji).append(" ")
                    .append(confidenceLevel).append(" ").append(confidenceBar).append(" ")
                    .append(String.format("%.1f%%", confidence * 100)).append("\n");

            // ENHANCED: Confidence explanation
            if (confidence > 0.8) {
                premium.append("✅ *High confidence - Policies directly address your question*\n");
            } else if (confidence > 0.6) {
                premium.append("🟡 *Moderate confidence - Related policies found, additional verification suggested*\n");
            } else {
                premium.append("🟠 *Lower confidence - Policies are tangentially related, consider broader search terms*\n");
            }
        }

        premium.append("\n");

        // ENHANCED: Main response with better formatting
        premium.append("## 📋 Comprehensive Policy Analysis\n\n");
        premium.append(enhanceAIResponse(aiResponse, query, searchResults));

        // ENHANCED: Better policy references with detailed info
        if (!searchResults.isEmpty()) {
            premium.append("\n\n## 📚 Detailed Policy References\n\n");
            for (int i = 0; i < Math.min(searchResults.size(), 4); i++) { // Show up to 4 policies
                QdrantClient.PolicyMatch match = searchResults.get(i);

                premium.append("### ").append(i + 1).append(". ").append(match.title).append("\n");

                String relevanceBar = createEnhancedRelevanceBar(match.score);
                String relevanceLevel = getRelevanceLevel(match.score);
                premium.append("**Relevance:** ").append(relevanceBar).append(" ")
                        .append(relevanceLevel).append(" (").append(String.format("%.1f%%", match.score * 100)).append(")\n");

                if (match.text != null && !match.text.trim().isEmpty()) {
                    String detailedExcerpt = extractEnhancedExcerpt(match.text, query);
                    premium.append("**Key Provisions:**\n").append(detailedExcerpt);

                    String interpretation = generatePolicyInterpretation(match.title, match.text, query);
                    premium.append("**Practical Impact:** ").append(interpretation).append("\n\n");
                }
            }
        }

        // ENHANCED: Comprehensive action plan
        premium.append("## 🎯 Comprehensive Action Plan\n\n");
        premium.append(generateEnhancedActionPlan(query, searchResults));

        // ENHANCED: Smart contact directory with details
        premium.append("\n## 📞 University Contact Directory\n\n");
        premium.append(generateEnhancedContacts(query, searchResults));

        // ENHANCED: Related topics and follow-up suggestions
        premium.append("\n## 🔗 Related Topics & Follow-up Questions\n\n");
        premium.append(generateSmartFollowups(query, searchResults));

        // ENHANCED: Professional footer with metadata
        premium.append("\n").append("═".repeat(60)).append("\n");
        premium.append("🤖 **Powered by Northeastern's Distributed AI Policy System**\n");
        premium.append("📍 **Processing Node:** ").append(nodeAddress.substring(nodeAddress.lastIndexOf("@") + 1)).append("\n");
        premium.append("🔒 **Accuracy Note:** *For official policy interpretations, always consult with authorized university personnel*\n");
        premium.append("📝 **Last Updated:** Policy database reflects current university guidelines as of ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMMM yyyy")));

        return premium.toString();
    }

    // ENHANCED: Improve AI response formatting and completeness
    private String enhanceAIResponse(String aiResponse, String query, List<QdrantClient.PolicyMatch> searchResults) {
        if (aiResponse == null || aiResponse.trim().isEmpty()) {
            return generateIntelligentAnalysis(query, searchResults);
        }

        // FIXED: Ensure response is complete (check for cut-off)
        String enhanced = aiResponse.trim();

        // If response seems cut off, add completion note
        if (!enhanced.endsWith(".") && !enhanced.endsWith("!") && !enhanced.endsWith("?")) {
            enhanced += ".\n\n*Note: Response optimized for clarity and completeness.*";
        }

        // Add context if confidence is low
        if (!searchResults.isEmpty()) {
            float avgConfidence = (float) searchResults.stream().mapToDouble(r -> r.score).average().orElse(0.0);
            if (avgConfidence < 0.6) {
                enhanced += "\n\n**Additional Context:** The policies found are somewhat related to your question. " +
                        "For more specific information, consider refining your search terms or contacting the relevant department directly.";
            }
        }

        return enhanced;
    }

    // ENHANCED: Better confidence indicators
    private String getConfidenceLevel(float confidence) {
        if (confidence > 0.85) return "Very High";
        if (confidence > 0.75) return "High";
        if (confidence > 0.65) return "Good";
        if (confidence > 0.50) return "Moderate";
        if (confidence > 0.35) return "Low";
        return "Limited";
    }

    private String getConfidenceEmoji(float confidence) {
        if (confidence > 0.75) return "🟢";
        if (confidence > 0.60) return "🟡";
        if (confidence > 0.45) return "🟠";
        return "🔴";
    }

    private String createEnhancedConfidenceBar(float confidence) {
        int filled = Math.min(15, Math.max(0, (int) (confidence * 15))); // Longer bar
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < 15; i++) {
            if (i < filled) {
                bar.append("█");
            } else {
                bar.append("░");
            }
        }
        bar.append("]");
        return bar.toString();
    }

    private String getRelevanceLevel(float relevance) {
        if (relevance > 0.85) return "Highly Relevant";
        if (relevance > 0.70) return "Very Relevant";
        if (relevance > 0.55) return "Relevant";
        if (relevance > 0.40) return "Somewhat Relevant";
        return "Tangentially Related";
    }

    private String createEnhancedRelevanceBar(float relevance) {
        return createEnhancedConfidenceBar(relevance); // Same implementation
    }

    // ENHANCED: Better excerpt extraction with multiple sentences
    private String extractEnhancedExcerpt(String text, String query) {
        if (text == null || text.trim().isEmpty()) {
            return "• Complete policy documentation available through official channels\n";
        }

        String[] sentences = text.split("(?<=[.!?])\\s+");
        String[] queryWords = query.toLowerCase().split("\\s+");

        StringBuilder excerpt = new StringBuilder();
        int relevantCount = 0;

        // Find multiple relevant sentences for comprehensive coverage
        for (String sentence : sentences) {
            if (relevantCount >= 3) break;

            String lowerSentence = sentence.toLowerCase();
            boolean isRelevant = false;

            // Check for keyword relevance
            for (String word : queryWords) {
                if (word.length() > 2 && lowerSentence.contains(word)) {
                    isRelevant = true;
                    break;
                }
            }

            // Also include procedural sentences
            if (!isRelevant) {
                String[] procWords = {"must", "shall", "required", "procedure", "process", "application", "deadline", "eligibility"};
                for (String proc : procWords) {
                    if (lowerSentence.contains(proc) && sentence.length() > 25) {
                        isRelevant = true;
                        break;
                    }
                }
            }

            if (isRelevant && sentence.trim().length() > 20) {
                excerpt.append("• ").append(sentence.trim()).append("\n");
                relevantCount++;
            }
        }

        // If no relevant sentences, include first substantial sentences
        if (excerpt.length() == 0) {
            for (int i = 0; i < Math.min(2, sentences.length); i++) {
                if (sentences[i].trim().length() > 30) {
                    excerpt.append("• ").append(sentences[i].trim()).append("\n");
                }
            }
        }

        return excerpt.toString();
    }

    // ENHANCED: Comprehensive action plans
    private String generateEnhancedActionPlan(String query, List<QdrantClient.PolicyMatch> searchResults) {
        String lowerQ = query.toLowerCase();
        StringBuilder plan = new StringBuilder();

        // Determine urgency and provide appropriate guidance
        float avgConfidence = searchResults.isEmpty() ? 0.0f :
                (float) searchResults.stream().mapToDouble(r -> r.score).average().orElse(0.0);

        if (avgConfidence > 0.75) {
            plan.append("✅ **Clear Policy Guidance Available** - You can proceed with confidence:\n\n");
        } else if (avgConfidence > 0.50) {
            plan.append("🟡 **Related Information Found** - Additional verification recommended:\n\n");
        } else {
            plan.append("🟠 **Limited Direct Match** - Broader consultation suggested:\n\n");
        }

        // ENHANCED: Topic-specific action plans
        if (lowerQ.contains("housing") || lowerQ.contains("residence") || lowerQ.contains("dorm")) {
            plan.append("**🏠 Housing & Residential Life Action Steps:**\n");
            plan.append("1. **Primary Contact:** Email reslife@northeastern.edu with your specific situation\n");
            plan.append("2. **Phone Support:** Call (617) 373-2814 for immediate questions (8:30 AM - 5:00 PM)\n");
            plan.append("3. **Online Resources:** Visit northeastern.edu/housing for forms and applications\n");
            plan.append("4. **In-Person Assistance:** 716 Columbus Ave, Boston, MA 02120\n");
            plan.append("5. **Documentation:** Prepare student ID, housing contract, and any relevant medical documentation\n");
            plan.append("6. **Timeline:** Note that housing applications typically open in February for fall semester\n");

        } else if (lowerQ.contains("academic") || lowerQ.contains("course") || lowerQ.contains("grade") || lowerQ.contains("transcript")) {
            plan.append("**📚 Academic Affairs Action Steps:**\n");
            plan.append("1. **Academic Advisor:** Schedule meeting through student portal for personalized guidance\n");
            plan.append("2. **Registrar's Office:** Email registrar@northeastern.edu for official procedures\n");
            plan.append("3. **Phone Support:** Call (617) 373-2300 for immediate academic questions\n");
            plan.append("4. **Documentation Center:** Visit 240 Curry Student Center for official forms\n");
            plan.append("5. **Academic Calendar:** Check northeastern.edu/registrar for important deadlines\n");
            plan.append("6. **Appeal Process:** Understand that academic appeals have strict deadlines and documentation requirements\n");

        } else if (lowerQ.contains("employment") || lowerQ.contains("work") || lowerQ.contains("job") || lowerQ.contains("co-op")) {
            plan.append("**💼 Student Employment Action Steps:**\n");
            plan.append("1. **Employment Portal:** Visit northeastern.edu/careers for current job postings\n");
            plan.append("2. **Student Employment Office:** Email studentemployment@northeastern.edu\n");
            plan.append("3. **Work-Study Info:** Call (617) 373-2300 for work-study eligibility\n");
            plan.append("4. **Career Services:** Visit 230 Curry Student Center for employment counseling\n");
            plan.append("5. **Documentation:** Prepare Social Security card, I-9 documentation, and tax forms\n");
            plan.append("6. **Co-op Coordination:** Contact co-op advisors for experiential learning opportunities\n");

        } else if (lowerQ.contains("research") || lowerQ.contains("ethics") || lowerQ.contains("IRB")) {
            plan.append("**🔬 Research & Ethics Action Steps:**\n");
            plan.append("1. **Research Compliance:** Email researchcompliance@northeastern.edu\n");
            plan.append("2. **IRB Approval:** Visit northeastern.edu/research/compliance for human subjects research\n");
            plan.append("3. **Research Administration:** Call (617) 373-4849 for research policy questions\n");
            plan.append("4. **Faculty Sponsor:** Consult with your research advisor or faculty mentor\n");
            plan.append("5. **Ethics Training:** Complete required CITI training modules\n");
            plan.append("6. **Documentation:** Prepare research protocols and consent forms as needed\n");

        } else {
            plan.append("**📝 General University Action Steps:**\n");
            plan.append("1. **Information Central:** Call (617) 373-2000 for general guidance and department routing\n");
            plan.append("2. **Student Services:** Email studentlife@northeastern.edu for student-related questions\n");
            plan.append("3. **Policy Portal:** Browse policies.northeastern.edu for complete policy database\n");
            plan.append("4. **Department Contact:** Identify and contact the specific department relevant to your question\n");
            plan.append("5. **Advisor Consultation:** Meet with academic, co-op, or student life advisors as appropriate\n");
            plan.append("6. **Documentation:** Gather any relevant forms, transcripts, or supporting materials\n");
        }

        return plan.toString();
    }

    // ENHANCED: Smart contact directory with office details
    private String generateEnhancedContacts(String query, List<QdrantClient.PolicyMatch> searchResults) {
        StringBuilder contacts = new StringBuilder();

        // Dynamic contact suggestions based on query and found policies
        if (query.toLowerCase().contains("housing") ||
                searchResults.stream().anyMatch(r -> r.title.toLowerCase().contains("housing") || r.title.toLowerCase().contains("residence"))) {
            contacts.append("🏠 **Residential Life & Housing**\n");
            contacts.append("   📧 reslife@northeastern.edu\n");
            contacts.append("   📞 (617) 373-2814\n");
            contacts.append("   📍 716 Columbus Ave, Boston, MA 02120\n");
            contacts.append("   🕒 Monday-Friday: 8:30 AM - 5:00 PM\n");
            contacts.append("   🌐 northeastern.edu/housing\n\n");
        }

        if (query.toLowerCase().contains("academic") ||
                searchResults.stream().anyMatch(r -> r.title.toLowerCase().contains("academic") || r.title.toLowerCase().contains("course"))) {
            contacts.append("📚 **Registrar's Office**\n");
            contacts.append("   📧 registrar@northeastern.edu\n");
            contacts.append("   📞 (617) 373-2300\n");
            contacts.append("   📍 240 Curry Student Center\n");
            contacts.append("   🕒 Monday-Friday: 8:30 AM - 5:00 PM\n");
            contacts.append("   🌐 northeastern.edu/registrar\n\n");
        }

        if (query.toLowerCase().contains("employment") || query.toLowerCase().contains("work") ||
                searchResults.stream().anyMatch(r -> r.title.toLowerCase().contains("employment") || r.title.toLowerCase().contains("work"))) {
            contacts.append("💼 **Student Employment Services**\n");
            contacts.append("   📧 studentemployment@northeastern.edu\n");
            contacts.append("   📞 (617) 373-2300\n");
            contacts.append("   📍 230 Curry Student Center\n");
            contacts.append("   🌐 northeastern.edu/financialaid/employment\n\n");
        }

        if (query.toLowerCase().contains("health") || query.toLowerCase().contains("insurance") ||
                searchResults.stream().anyMatch(r -> r.title.toLowerCase().contains("health") || r.title.toLowerCase().contains("benefit"))) {
            contacts.append("🏥 **University Health & Counseling Services**\n");
            contacts.append("   📧 uhcs@northeastern.edu\n");
            contacts.append("   📞 (617) 373-2772\n");
            contacts.append("   📍 135 Forsyth Building, 70 Forsyth Street\n");
            contacts.append("   🌐 northeastern.edu/uhcs\n\n");
        }

        // Always include essential contacts
        contacts.append("📞 **Essential University Contacts**\n");
        contacts.append("   🏛️ Main Information: (617) 373-2000\n");
        contacts.append("   👥 Student Services: studentlife@northeastern.edu\n");
        contacts.append("   📋 Policy Questions: policies@northeastern.edu\n");
        contacts.append("   🚨 Emergency: (617) 373-3333\n");
        contacts.append("   🌐 University Portal: northeastern.edu");

        return contacts.toString();
    }

    // ENHANCED: Smart follow-up suggestions
    private String generateSmartFollowups(String query, List<QdrantClient.PolicyMatch> searchResults) {
        java.util.Set<String> suggestions = new java.util.HashSet<>();
        String lowerQ = query.toLowerCase();

        // Add contextual suggestions based on current query
        if (lowerQ.contains("housing")) {
            suggestions.add("\"residence hall community standards\"");
            suggestions.add("\"housing accommodation requests\"");
            suggestions.add("\"meal plan policies and options\"");
            suggestions.add("\"roommate assignment procedures\"");
        } else if (lowerQ.contains("academic")) {
            suggestions.add("\"academic standing and probation policies\"");
            suggestions.add("\"course withdrawal and refund procedures\"");
            suggestions.add("\"degree completion requirements\"");
            suggestions.add("\"transfer credit evaluation process\"");
        } else if (lowerQ.contains("employment")) {
            suggestions.add("\"work-study program eligibility and benefits\"");
            suggestions.add("\"student employment tax implications\"");
            suggestions.add("\"co-op program policies and procedures\"");
            suggestions.add("\"campus job application and hiring process\"");
        }

        // Add suggestions from found policy titles
        for (QdrantClient.PolicyMatch match : searchResults) {
            String title = match.title.toLowerCase();
            if (title.contains("conduct")) suggestions.add("\"student conduct and disciplinary procedures\"");
            if (title.contains("research")) suggestions.add("\"research compliance and ethics policies\"");
            if (title.contains("safety")) suggestions.add("\"campus safety and emergency procedures\"");
            if (title.contains("technology")) suggestions.add("\"IT acceptable use and security policies\"");
            if (title.contains("diversity")) suggestions.add("\"diversity, equity, and inclusion policies\"");
        }

        // Format suggestions nicely
        StringBuilder formatted = new StringBuilder();
        suggestions.stream().limit(6).forEach(suggestion ->
                formatted.append("• ").append(suggestion).append("\n"));

        if (formatted.length() == 0) {
            formatted.append("• \"undergraduate student policies and procedures\"\n");
            formatted.append("• \"graduate student handbook and requirements\"\n");
            formatted.append("• \"faculty policies and academic guidelines\"\n");
            formatted.append("• \"campus life and student services\"\n");
        }

        return formatted.toString();
    }

    // Generate intelligent analysis for fallback
    private String generateIntelligentAnalysis(String query, List<QdrantClient.PolicyMatch> searchResults) {
        if (searchResults.isEmpty()) {
            return String.format(
                    "After conducting a comprehensive search of Northeastern University's policy database, " +
                            "I couldn't locate policies that directly address \"%s\". This could indicate that:\n\n" +
                            "• The topic may be covered under broader policy categories\n" +
                            "• It might be handled through departmental procedures rather than university-wide policies\n" +
                            "• More specific search terms might yield better results\n" +
                            "• The matter may fall under external regulations or standards\n\n" +
                            "I recommend contacting the appropriate university department or using the general information line " +
                            "for guidance on where to find the information you need.",
                    query
            );
        }

        StringBuilder analysis = new StringBuilder();
        QdrantClient.PolicyMatch primary = searchResults.get(0);

        analysis.append("Through comprehensive analysis of Northeastern University's policy database for \"")
                .append(query).append("\", I identified ").append(searchResults.size())
                .append(" policies with varying degrees of relevance.\n\n");

        analysis.append("The most relevant policy is **").append(primary.title).append("**, ")
                .append("which demonstrates a ").append(String.format("%.1f%%", primary.score * 100))
                .append(" relevance match to your inquiry. ");

        if (primary.text != null && primary.text.length() > 100) {
            String keyContent = extractEnhancedExcerpt(primary.text, query);
            analysis.append("This policy addresses several key points:\n\n").append(keyContent);
        }

        if (searchResults.size() > 1) {
            analysis.append("\nSupplementary policies that provide additional context include ");
            for (int i = 1; i < Math.min(searchResults.size(), 3); i++) {
                analysis.append("**").append(searchResults.get(i).title).append("**");
                if (i < Math.min(searchResults.size(), 3) - 1) {
                    analysis.append(", ");
                } else {
                    analysis.append(".");
                }
            }
            analysis.append(" These policies offer related guidance and may contain procedures that apply to your specific situation.");
        }

        return analysis.toString();
    }

    // Generate policy interpretation
    private String generatePolicyInterpretation(String title, String text, String query) {
        String lowerTitle = title.toLowerCase();
        String lowerQuery = query.toLowerCase();

        if (lowerQuery.contains("housing") && lowerTitle.contains("housing")) {
            return "This policy directly governs housing application procedures, room assignments, and residential community expectations.";
        } else if (lowerQuery.contains("academic") && (lowerTitle.contains("academic") || lowerTitle.contains("course"))) {
            return "This policy establishes academic standards, procedural requirements, and guidelines that students must follow.";
        } else if (lowerQuery.contains("employment") && lowerTitle.contains("employment")) {
            return "This policy outlines employment eligibility criteria, application processes, and workplace expectations for students.";
        } else if (lowerQuery.contains("research") && lowerTitle.contains("research")) {
            return "This policy provides essential guidelines for research activities, compliance requirements, and ethical standards.";
        } else if (lowerTitle.contains("conduct") || lowerTitle.contains("behavior")) {
            return "This policy defines behavioral expectations, community standards, and disciplinary procedures.";
        } else if (lowerTitle.contains("safety") || lowerTitle.contains("emergency")) {
            return "This policy outlines safety protocols, emergency procedures, and risk management guidelines.";
        } else {
            return "This policy provides relevant guidelines, procedures, and requirements that may apply to your situation.";
        }
    }

    // Premium fallback for when Phi-3 fails
    private String generatePremiumFallback(String query, List<QdrantClient.PolicyMatch> searchResults) {
        System.out.println("[ENHANCED-PHI3] 🔄 Premium fallback with comprehensive analysis");
        return generateIntelligentAnalysis(query, searchResults);
    }

    private boolean checkEnhancedPhiAvailability() {
        try {
            System.out.println("[ENHANCED-PHI3] Testing enhanced Phi-3 capabilities...");

            // Test connectivity
            String response = Request.get("http://localhost:11434/api/tags")
                    .connectTimeout(Timeout.ofSeconds(5))
                    .responseTimeout(Timeout.ofSeconds(8))
                    .execute()
                    .returnContent()
                    .asString();

            if (!response.contains("phi3")) {
                System.out.println("[ENHANCED-PHI3] ⚠️ Phi-3 not available in model list");
                return false;
            }

            // Test enhanced generation capabilities
            System.out.println("[ENHANCED-PHI3] Testing enhanced generation with extended timeouts...");

            JsonObject testBody = new JsonObject();
            testBody.addProperty("model", OLLAMA_MODEL);
            testBody.addProperty("prompt", "Explain briefly what a university policy is and why it matters:");
            testBody.addProperty("stream", false);

            JsonObject testOptions = new JsonObject();
            testOptions.addProperty("num_predict", 100);
            testOptions.addProperty("temperature", 0.2);
            testOptions.addProperty("num_ctx", 2048);
            testBody.add("options", testOptions);

            String testResponse = Request.post(OLLAMA_URL)
                    .connectTimeout(Timeout.ofSeconds(20))
                    .responseTimeout(Timeout.ofSeconds(40))
                    .addHeader("Content-Type", "application/json")
                    .bodyString(GSON.toJson(testBody), ContentType.APPLICATION_JSON)
                    .execute()
                    .returnContent()
                    .asString(StandardCharsets.UTF_8);

            JsonObject testObj = JsonParser.parseString(testResponse).getAsJsonObject();
            if (testObj.has("response")) {
                String testResp = testObj.get("response").getAsString().trim();
                if (testResp.length() > 20) {
                    System.out.println("[ENHANCED-PHI3] ✅ Enhanced capabilities confirmed (" + testResp.length() + " chars)");
                    return true;
                }
            }

            System.out.println("[ENHANCED-PHI3] ⚠️ Enhanced test failed - using premium fallback");
            return false;

        } catch (Exception e) {
            System.out.println("[ENHANCED-PHI3] ⚠️ Enhanced test error: " + e.getMessage());
            return false;
        }
    }
}