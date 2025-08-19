package com.northeastern.scraper;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.receptionist.Receptionist;
import akka.cluster.ClusterEvent;
import akka.cluster.Member;
import akka.cluster.typed.Cluster;
import akka.cluster.typed.Subscribe;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FIXED Frontend actor - no infinite loops, clean user interaction
 */
public class PolicyFrontend extends AbstractBehavior<PolicyFrontend.Command> {

    public interface Command extends PolicySerializable {}

    public static final class UserQuery implements Command {
        public final String queryId;
        public final String question;

        @JsonCreator
        public UserQuery(
                @JsonProperty("queryId") String queryId,
                @JsonProperty("question") String question
        ) {
            this.queryId = queryId;
            this.question = question;
        }
    }

    private static final class StartUserInterface implements Command {}

    private static final class ProcessUserInput implements Command {
        public final String input;
        public ProcessUserInput(String input) { this.input = input; }
    }

    private static final class ClusterMemberEvent implements Command {
        public final ClusterEvent.ClusterDomainEvent event;
        public ClusterMemberEvent(ClusterEvent.ClusterDomainEvent event) { this.event = event; }
    }

    private static final class SearchResultReceived implements Command {
        public final String queryId;
        public final String originalQuery;  // FIXED: Store original query
        public final PolicyBackendWorker.PolicySearchResponse searchResponse;

        public SearchResultReceived(String queryId, String originalQuery, PolicyBackendWorker.PolicySearchResponse searchResponse) {
            this.queryId = queryId;
            this.originalQuery = originalQuery;  // FIXED: Store original query
            this.searchResponse = searchResponse;
        }
    }

    private static final class LLMResultReceived implements Command {
        public final String queryId;
        public final PolicyLLMService.GenerationResponse llmResponse;

        public LLMResultReceived(String queryId, PolicyLLMService.GenerationResponse llmResponse) {
            this.queryId = queryId;
            this.llmResponse = llmResponse;
        }
    }

    private final AtomicInteger queryCounter = new AtomicInteger(0);
    private final List<String> availableBackends = new ArrayList<>();
    private final String nodeAddress;

    public static Behavior<Command> create() {
        return Behaviors.setup(PolicyFrontend::new);
    }

    private PolicyFrontend(ActorContext<Command> context) {
        super(context);

        Cluster cluster = Cluster.get(context.getSystem());
        this.nodeAddress = cluster.selfMember().address().toString();

        // Subscribe to cluster events
        ActorRef<ClusterEvent.ClusterDomainEvent> clusterEventAdapter =
                context.messageAdapter(ClusterEvent.ClusterDomainEvent.class, ClusterMemberEvent::new);
        cluster.subscriptions().tell(Subscribe.create(clusterEventAdapter, ClusterEvent.ClusterDomainEvent.class));

        System.out.println("🌐 Policy Frontend started on " + nodeAddress);

        // Start user interface immediately
        context.getSelf().tell(new StartUserInterface());
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(UserQuery.class, this::onUserQuery)
                .onMessage(StartUserInterface.class, this::onStartUserInterface)
                .onMessage(ProcessUserInput.class, this::onProcessUserInput)
                .onMessage(ClusterMemberEvent.class, this::onClusterMemberEvent)
                .onMessage(SearchResultReceived.class, this::onSearchResultReceived)
                .onMessage(LLMResultReceived.class, this::onLLMResultReceived)
                .build();
    }

    private Behavior<Command> onStartUserInterface(StartUserInterface command) {
        System.out.println("\n🤖 Policy Bot Cluster Frontend Ready!");
        System.out.println("=====================================");
        System.out.println("📋 Available backends: " + availableBackends.size());
        System.out.println("🎯 Demonstrating: tell, ask, and forward patterns");
        System.out.println("=====================================\n");

        // Start simple user interface
        startSimpleUserInterface();

        return this;
    }

    // FIXED: Simple user interface without threading issues
    private void startSimpleUserInterface() {
        CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
                while (true) {
                    System.out.print("Ask a policy question (or 'exit'): ");
                    String input = reader.readLine();

                    if (input == null || "exit".equalsIgnoreCase(input.trim())) {
                        System.out.println("👋 Shutting down cluster frontend...");
                        getContext().getSystem().terminate();
                        break;
                    }

                    if (!input.trim().isEmpty()) {
                        // FIXED: Process the actual user input, not query ID
                        String queryId = "query-" + queryCounter.incrementAndGet();
                        System.out.println("DEBUG - QueryID: " + queryId);
                        System.out.println("DEBUG - Question: " + input.trim());
                        getContext().getSelf().tell(new UserQuery(queryId, input.trim()));

                        // Give some time for processing
                        Thread.sleep(1000);
                    }
                }
            } catch (Exception e) {
                System.err.println("❌ Input error: " + e.getMessage());
            }
        });
    }

    private Behavior<Command> onProcessUserInput(ProcessUserInput command) {
        // This method is no longer needed but kept for compatibility
        return this;
    }

    private Behavior<Command> onUserQuery(UserQuery query) {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("🎯 PROCESSING QUERY: " + query.question);  // FIXED: Use query.question
        System.out.println("🆔 Query ID: " + query.queryId);
        System.out.println("=".repeat(70));

        // DEMONSTRATE: tell pattern
        System.out.println("\n--- 📤 DEMONSTRATING TELL PATTERN ---");
        ActorRef<PolicyLogger.Command> logger = getContext().spawn(PolicyLogger.create(), "logger-" + query.queryId);
        logger.tell(new PolicyLogger.LogEvent("User query: " + query.question, "frontend"));  // FIXED: Use query.question
        logger.tell(new PolicyLogger.LogEvent("Starting cluster processing", "frontend"));
        System.out.println("✅ TELL: Query logged with fire-and-forget messages");

        if (availableBackends.isEmpty()) {
            System.out.println("⚠️ No backend workers available. Please start backend nodes.");
            System.out.print("\nAsk a policy question (or 'exit'): ");
            return this;
        }

        // DEMONSTRATE: ask pattern
        System.out.println("\n--- 🔄 DEMONSTRATING ASK PATTERN ---");
        System.out.println("ASK-1: Requesting backend services...");

        // Find backend services
        ActorRef<Receptionist.Listing> listingAdapter =
                getContext().messageAdapter(Receptionist.Listing.class, listing -> {
                    Set<ActorRef<PolicyBackendWorker.Command>> workers =
                            listing.getServiceInstances(PolicyBackendWorker.POLICY_WORKER_SERVICE_KEY);

                    if (!workers.isEmpty()) {
                        ActorRef<PolicyBackendWorker.Command> worker = workers.iterator().next();
                        System.out.println("✅ ASK-1: Found backend, requesting search for: " + query.question);

                        // FIXED: Request search results with proper query
                        ActorRef<PolicyBackendWorker.PolicySearchResponse> searchAdapter =
                                getContext().messageAdapter(PolicyBackendWorker.PolicySearchResponse.class,
                                        response -> new SearchResultReceived(query.queryId, query.question, response));  // FIXED: Pass original query

                        worker.tell(new PolicyBackendWorker.PolicySearchRequest(
                                query.queryId, query.question, searchAdapter));  // FIXED: Use query.question, not query.queryId

                        logger.tell(new PolicyLogger.LogEvent("Search request sent to backend", "frontend"));

                    } else {
                        System.out.println("❌ ASK-1: No backend workers found");
                        System.out.print("\nAsk a policy question (or 'exit'): ");
                    }

                    return new ProcessUserInput(""); // Empty input to avoid processing
                });

        getContext().getSystem().receptionist().tell(
                Receptionist.find(PolicyBackendWorker.POLICY_WORKER_SERVICE_KEY, listingAdapter));

        return this;
    }

    private Behavior<Command> onSearchResultReceived(SearchResultReceived event) {
        System.out.println("✅ ASK-1: Search completed by " + event.searchResponse.processedBy);
        System.out.println("📊 Found " + event.searchResponse.results.size() + " relevant policies");

        // DEMONSTRATE: ask pattern to LLM
        System.out.println("ASK-2: Requesting LLM generation...");

        ActorRef<Receptionist.Listing> llmListingAdapter =
                getContext().messageAdapter(Receptionist.Listing.class, listing -> {
                    Set<ActorRef<PolicyLLMService.Command>> llmServices =
                            listing.getServiceInstances(PolicyLLMService.POLICY_LLM_SERVICE_KEY);

                    if (!llmServices.isEmpty()) {
                        ActorRef<PolicyLLMService.Command> llmService = llmServices.iterator().next();
                        System.out.println("✅ ASK-2: Found LLM service, generating response...");

                        ActorRef<PolicyLLMService.GenerationResponse> llmAdapter =
                                getContext().messageAdapter(PolicyLLMService.GenerationResponse.class,
                                        response -> new LLMResultReceived(event.queryId, response));

                        // FIXED: Use original query, not query ID
                        llmService.tell(new PolicyLLMService.GenerateResponse(
                                event.queryId, event.originalQuery, event.searchResponse.results, llmAdapter));

                    } else {
                        System.out.println("❌ ASK-2: No LLM services found");
                        System.out.print("\nAsk a policy question (or 'exit'): ");
                    }

                    return new ProcessUserInput(""); // Empty to avoid loops
                });

        getContext().getSystem().receptionist().tell(
                Receptionist.find(PolicyLLMService.POLICY_LLM_SERVICE_KEY, llmListingAdapter));

        return this;
    }

    private Behavior<Command> onLLMResultReceived(LLMResultReceived event) {
        System.out.println("✅ ASK-2: LLM generation completed by " + event.llmResponse.processedBy);
        System.out.println("⚡ Processing time: " + event.llmResponse.processingTimeMs + "ms");

        // DEMONSTRATE: forward pattern
        System.out.println("\n--- 🔄 DEMONSTRATING FORWARD PATTERN ---");

        ActorRef<PolicyLogger.Command> finalLogger = getContext().spawn(PolicyLogger.create(), "final-" + event.queryId);

        // FIXED: Simple forward without creating more message loops
        finalLogger.tell(new PolicyLogger.LogEvent(
                "FORWARD: Query " + event.queryId + " completed successfully", "frontend"));

        System.out.println("✅ FORWARD: Response logged and processing complete");

        // Display final result
        displayFinalResult(event.queryId, event.llmResponse);

        // IMPORTANT: Return to user input prompt
        System.out.print("\nAsk a policy question (or 'exit'): ");

        return this;
    }

    private void displayFinalResult(String queryId, PolicyLLMService.GenerationResponse response) {
        System.out.println("\n" + "🎉".repeat(20));
        System.out.println("📋 CLUSTER PROCESSING COMPLETE");
        System.out.println("🎉".repeat(20));
        System.out.println("🆔 Query ID: " + queryId);
        System.out.println("🔧 Processed by: " + response.processedBy);
        System.out.println("⚡ Processing time: " + response.processingTimeMs + "ms");
        System.out.println("🤖 AI Generation: " + (response.success ? "SUCCESS" : "FALLBACK"));
        System.out.println("\n📄 ANSWER:");
        System.out.println(response.answer);
        System.out.println("\n🎯 PATTERNS DEMONSTRATED:");
        System.out.println("  ✅ TELL: Fire-and-forget logging");
        System.out.println("  ✅ ASK: Backend search + LLM generation");
        System.out.println("  ✅ FORWARD: Message delegation");
        System.out.println("  ✅ CLUSTER: 3-node distributed processing");
        System.out.println("🎉".repeat(20));
    }

    private Behavior<Command> onClusterMemberEvent(ClusterMemberEvent memberEvent) {
        ClusterEvent.ClusterDomainEvent event = memberEvent.event;

        if (event instanceof ClusterEvent.MemberUp) {
            ClusterEvent.MemberUp memberUp = (ClusterEvent.MemberUp) event;
            Member member = memberUp.member();

            if (member.hasRole("backend")) {
                availableBackends.add(member.address().toString());
                System.out.println("🔧 Backend available: " + member.address() + " (Total: " + availableBackends.size() + ")");
            }
        } else if (event instanceof ClusterEvent.MemberRemoved) {
            ClusterEvent.MemberRemoved memberRemoved = (ClusterEvent.MemberRemoved) event;
            Member member = memberRemoved.member();

            if (member.hasRole("backend")) {
                availableBackends.remove(member.address().toString());
                System.out.println("🔧 Backend unavailable: " + member.address() + " (Total: " + availableBackends.size() + ")");
            }
        }

        return this;
    }
}