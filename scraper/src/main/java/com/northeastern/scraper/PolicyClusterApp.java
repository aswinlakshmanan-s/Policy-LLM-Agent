package com.northeastern.scraper;

import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Behaviors;
import akka.cluster.typed.Cluster;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Main application class for Policy Bot Cluster demonstration.
 *
 * Creates different types of cluster nodes:
 * - seed: Seed node for cluster formation
 * - backend: Backend processing node (search + LLM)
 * - frontend: Frontend node for user interaction
 *
 * Usage:
 * java PolicyClusterApp seed
 * java PolicyClusterApp backend 2552
 * java PolicyClusterApp frontend 2553
 */
public class PolicyClusterApp {

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("🤖 Policy Bot Cluster");
            System.out.println("Usage: java PolicyClusterApp <node-type> [port]");
            System.out.println("Node types: seed, backend, frontend");
            System.out.println("Example: java PolicyClusterApp seed");
            System.out.println("Example: java PolicyClusterApp backend 2552");
            System.out.println("Example: java PolicyClusterApp frontend 2553");
            System.exit(1);
        }

        String nodeType = args[0];
        int port = args.length > 1 ? Integer.parseInt(args[1]) : getDefaultPort(nodeType);

        startNode(nodeType, port);
    }

    private static int getDefaultPort(String nodeType) {
        return switch (nodeType) {
            case "seed" -> 2551;
            case "backend" -> 2552;
            case "frontend" -> 2553;
            default -> 2554;
        };
    }

    private static void startNode(String nodeType, int port) {
        Config config = createConfig(nodeType, port);

        ActorSystem<Void> system = ActorSystem.create(
                getRootBehavior(nodeType),
                "PolicyClusterSystem",
                config
        );

        System.out.println(String.format(
                "🚀 Starting %s node on port %d", nodeType, port
        ));

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down " + nodeType + " node...");
            system.terminate();
        }));
    }

    private static Config createConfig(String nodeType, int port) {
        String configString = String.format("""
            akka {
              actor {
                provider = "cluster"
                
                serialization-bindings {
                  "com.northeastern.scraper.PolicySerializable" = jackson-cbor
                }
                
                allow-java-serialization = on
                warn-about-java-serializer-usage = off
              }
              
              remote.artery {
                canonical {
                  hostname = "127.0.0.1"
                  port = %d
                }
              }
              
              cluster {
                seed-nodes = [
                  "akka://PolicyClusterSystem@127.0.0.1:2551"
                ]
                
                roles = ["%s"]
                
                # Auto-down for development
                auto-down-unreachable-after = 10s
                
                # Log cluster events
                log-info = on
              }
              
              loglevel = "INFO"
            }
            """, port, nodeType);

        return ConfigFactory.parseString(configString)
                .withFallback(ConfigFactory.load());
    }

    private static Behavior<Void> getRootBehavior(String nodeType) {
        return Behaviors.setup(context -> {
            Cluster cluster = Cluster.get(context.getSystem());

            // Create appropriate actors based on node type
            switch (nodeType) {
                case "seed":
                    context.spawn(PolicyClusterListener.create(), "cluster-listener");
                    System.out.println("📡 Seed node started. Ready for other nodes to join.");
                    break;

                case "backend":
                    context.spawn(PolicyClusterListener.create(), "cluster-listener");
                    // Initialize backend services asynchronously
                    initializeBackendServices(context);
                    System.out.println("🔧 Backend processing node started.");
                    break;

                case "frontend":
                    context.spawn(PolicyClusterListener.create(), "cluster-listener");
                    context.spawn(PolicyFrontend.create(), "policy-frontend");
                    System.out.println("🌐 Frontend node started.");
                    break;

                default:
                    System.out.println("❌ Unknown node type: " + nodeType);
                    context.getSystem().terminate();
                    break;
            }

            return Behaviors.empty();
        });
    }

    private static void initializeBackendServices(akka.actor.typed.javadsl.ActorContext<Void> context) {
        try {
            System.out.println("[BACKEND] Initializing policy processing services...");

            // Try to initialize dependencies
            var embedder = new EmbeddingService(null);
            var qdrant = new QdrantClient("http://127.0.0.1:6333");

            // Test connections
            embedder.embed("test");
            long count = qdrant.countPoints();

            // Create backend actors
            context.spawn(PolicyBackendWorker.create(embedder, qdrant), "policy-worker");
            context.spawn(PolicyLLMService.create(), "llm-service");
            context.spawn(PolicyLogger.create(), "policy-logger");

            System.out.println("[BACKEND] ✅ All services ready. Policies: " + count);

        } catch (Exception e) {
            System.err.println("[BACKEND] ⚠️ Service initialization failed: " + e.getMessage());
            System.err.println("[BACKEND] Starting in limited mode...");

            // Create limited services
            context.spawn(PolicyBackendWorker.createLimited(), "policy-worker");
            context.spawn(PolicyLLMService.create(), "llm-service");
            context.spawn(PolicyLogger.create(), "policy-logger");
        }
    }
}