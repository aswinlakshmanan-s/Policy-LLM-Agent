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
import ai.djl.translate.TranslateException;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * UPDATED Backend worker - enhanced search with more results
 */
public class PolicyBackendWorker extends AbstractBehavior<PolicyBackendWorker.Command> {

    // Service key for cluster registration
    public static final ServiceKey<Command> POLICY_WORKER_SERVICE_KEY =
            ServiceKey.create(Command.class, "policy-worker-service");

    // Command messages
    public interface Command extends PolicySerializable {}

    public static final class PolicySearchRequest implements Command {
        public final String requestId;
        public final String query;
        public final ActorRef<PolicySearchResponse> replyTo;

        @JsonCreator
        public PolicySearchRequest(
                @JsonProperty("requestId") String requestId,
                @JsonProperty("query") String query,
                @JsonProperty("replyTo") ActorRef<PolicySearchResponse> replyTo
        ) {
            this.requestId = requestId;
            this.query = query;
            this.replyTo = replyTo;
        }
    }

    public static final class PolicySearchResponse implements PolicySerializable {
        public final String requestId;
        public final List<QdrantClient.PolicyMatch> results;
        public final String processedBy;
        public final boolean success;

        @JsonCreator
        public PolicySearchResponse(
                @JsonProperty("requestId") String requestId,
                @JsonProperty("results") List<QdrantClient.PolicyMatch> results,
                @JsonProperty("processedBy") String processedBy,
                @JsonProperty("success") boolean success
        ) {
            this.requestId = requestId;
            this.results = results;
            this.processedBy = processedBy;
            this.success = success;
        }
    }

    private static final class SearchProcessingComplete implements Command {
        public final PolicySearchRequest originalRequest;
        public final List<QdrantClient.PolicyMatch> results;
        public final boolean success;

        public SearchProcessingComplete(PolicySearchRequest originalRequest, List<QdrantClient.PolicyMatch> results, boolean success) {
            this.originalRequest = originalRequest;
            this.results = results;
            this.success = success;
        }
    }

    private final EmbeddingService embedder;
    private final QdrantClient qdrant;
    private final String nodeAddress;
    private final boolean fullFunctionality;

    // Full functionality constructor
    public static Behavior<Command> create(EmbeddingService embedder, QdrantClient qdrant) {
        return Behaviors.setup(context -> new PolicyBackendWorker(context, embedder, qdrant, true));
    }

    // Limited functionality constructor
    public static Behavior<Command> createLimited() {
        return Behaviors.setup(context -> new PolicyBackendWorker(context, null, null, false));
    }

    private PolicyBackendWorker(ActorContext<Command> context, EmbeddingService embedder, QdrantClient qdrant, boolean fullFunctionality) {
        super(context);
        this.embedder = embedder;
        this.qdrant = qdrant;
        this.fullFunctionality = fullFunctionality;

        Cluster cluster = Cluster.get(context.getSystem());
        this.nodeAddress = cluster.selfMember().address().toString();

        // REGISTER with cluster receptionist
        context.getSystem().receptionist().tell(
                Receptionist.register(POLICY_WORKER_SERVICE_KEY, context.getSelf())
        );

        System.out.println("🔧 Enhanced policy worker registered on " + nodeAddress);
        System.out.println("🔍 Search functionality: " + (fullFunctionality ? "FULL (Enhanced Results)" : "LIMITED"));
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(PolicySearchRequest.class, this::onPolicySearchRequest)
                .onMessage(SearchProcessingComplete.class, this::onSearchProcessingComplete)
                .build();
    }

    private Behavior<Command> onPolicySearchRequest(PolicySearchRequest request) {
        System.out.println(String.format(
                "🔍 Processing enhanced policy search '%s': %s",
                request.requestId,
                request.query
        ));

        if (fullFunctionality) {
            // UPDATED: Enhanced search with more results and better processing
            CompletableFuture.supplyAsync(() -> {
                try {
                    System.out.println("[WORKER] Creating embedding for enhanced search: " + request.query);
                    long startTime = System.currentTimeMillis();

                    // Create embedding
                    float[] embedding = embedder.embed(request.query);

                    // UPDATED: Get more results for comprehensive analysis
                    List<QdrantClient.PolicyMatch> results = qdrant.search(embedding, 5); // Increased from 3 to 5

                    // Log detailed results
                    long processingTime = System.currentTimeMillis() - startTime;
                    System.out.println("[WORKER] ✅ Enhanced search completed in " + processingTime + "ms");
                    System.out.println("[WORKER] Found " + results.size() + " relevant policies:");

                    for (int i = 0; i < results.size(); i++) {
                        QdrantClient.PolicyMatch match = results.get(i);
                        System.out.println(String.format("[WORKER]   %d. %s (%.1f%% relevant)",
                                i + 1, match.title, match.score * 100));
                    }

                    return new SearchProcessingComplete(request, results, true);

                } catch (TranslateException | java.io.IOException e) {
                    System.err.println("[WORKER] ❌ Enhanced search failed: " + e.getMessage());
                    return new SearchProcessingComplete(request, java.util.List.of(), false);
                }
            }).thenAccept(result -> getContext().getSelf().tell(result));

        } else {
            // UPDATED: Enhanced mock results with more detail
            System.out.println("[WORKER] Limited mode - creating enhanced mock results");

            List<QdrantClient.PolicyMatch> enhancedMockResults = java.util.List.of(
                    new QdrantClient.PolicyMatch(
                            "Mock Policy 1: " + request.query + " Guidelines",
                            "This is a comprehensive mock policy result demonstrating cluster functionality. " +
                                    "The distributed system is working correctly with service discovery, cross-node communication, " +
                                    "and proper message routing. Query processed: " + request.query + ". " +
                                    "This mock result includes detailed policy information that would normally come from " +
                                    "the vector database search. In a production environment, this would contain actual " +
                                    "policy text with specific guidelines, procedures, and requirements.",
                            0.92f
                    ),
                    new QdrantClient.PolicyMatch(
                            "Mock Policy 2: " + request.query + " Procedures",
                            "Additional comprehensive policy information related to " + request.query + ". " +
                                    "This demonstrates how the system can return multiple relevant policy matches " +
                                    "with different relevance scores. The cluster-based architecture allows for " +
                                    "distributed processing and load balancing across multiple backend nodes.",
                            0.87f
                    ),
                    new QdrantClient.PolicyMatch(
                            "Mock Policy 3: " + request.query + " Best Practices",
                            "Supplementary policy guidance and best practices for " + request.query + ". " +
                                    "This shows how the system can provide comprehensive coverage of related topics " +
                                    "and cross-referenced policies to give users complete information.",
                            0.81f
                    )
            );

            getContext().getSelf().tell(new SearchProcessingComplete(request, enhancedMockResults, true));
        }

        return this;
    }

    private Behavior<Command> onSearchProcessingComplete(SearchProcessingComplete complete) {
        PolicySearchRequest originalRequest = complete.originalRequest;

        PolicySearchResponse response = new PolicySearchResponse(
                originalRequest.requestId,
                complete.results,
                nodeAddress,
                complete.success
        );

        System.out.println(String.format(
                "✅ Enhanced policy search completed '%s': %d comprehensive results found",
                originalRequest.requestId,
                complete.results.size()
        ));

        // Show result summary
        if (!complete.results.isEmpty()) {
            System.out.println("[WORKER] Result summary:");
            for (int i = 0; i < Math.min(complete.results.size(), 3); i++) {
                QdrantClient.PolicyMatch match = complete.results.get(i);
                System.out.println(String.format("[WORKER]   • %s (%.1f%% match)",
                        match.title, match.score * 100));
            }
        }

        // DEMONSTRATE: tell pattern - send comprehensive response back
        originalRequest.replyTo.tell(response);

        return this;
    }
}