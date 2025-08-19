# Distributed Policy Assistant System (Akka Cluster + LLM)

**Production-grade, distributed AI assistant** that turns dense university (or enterprise) policy documents into fast, trustworthy answers.  
Built with **Akka Cluster (Java, Akka Typed)**, **RAG** (vector search + LLM), and **observable, fault-tolerant** runtime patterns.

> **Highlights**
> - 3-node **Akka Cluster** with **tell / ask / forward** patterns  
> - **RAG pipeline**: Qdrant (vector DB) + Microsoft Phi-3 Mini (via Ollama)  
> - **Horizontal scalability**, **graceful degradation**, **real-time monitoring**  
> - Clean separation of **frontend orchestration**, **backend search**, **LLM generation**, **logging/audit**

---

## Table of Contents
- [Architecture](#architecture)
- [Features](#features)
- [Tech Stack](#tech-stack)
- [Quick Start](#quick-start)
- [Configuration](#configuration)
- [Run the Cluster](#run-the-cluster)
- [Observability & Ops](#observability--ops)
- [Testing](#testing)
- [Benchmarks](#benchmarks)
- [Production Hardening](#production-hardening)
- [Troubleshooting](#troubleshooting)

---

## Architecture

```
                ┌───────────────────────────────┐
                │         SEED NODE (2551)      │
                │  • Cluster bootstrap          │
                │  • Service registry           │
                │  • Cluster listener           │
                └───────────────┬───────────────┘
                                │ Akka Artery (TCP + CBOR)
     ┌──────────────────────────┴───────────────────────────┐
     │                                                      │
┌────▼─────┐                                           ┌────▼─────┐
│ BACKEND  │                                           │ FRONTEND │
│ (2552)   │                                           │ (2553)   │
│ • PolicyBackendWorker (vector search, Qdrant)        │ • PolicyFrontend
│ • PolicyLLMService (RAG, Ollama Phi-3 Mini)          │   (orchestration)
│ • PolicyLogger (audit, forward)                      │ • Cluster listener
└──────────┘                                           └──────────┘
```

**Message patterns**
- **tell**: fire-and-forget logging, non-critical signals  
- **ask**: request/response (search → LLM → response)  
- **forward**: delegated routing + sender preservation for auditability

**RAG flow**
1. Embed query text → 384-d vector (MiniLM family)  
2. Similarity search in **Qdrant** (top-k policies + payload)  
3. Construct RAG prompt → **Phi-3 Mini** via **Ollama**  
4. Stream/return robust, referenced answer + confidence

---

## Features
- **Distributed & resilient**: multi-node cluster, service discovery, automatic member up/down handling
- **AI-native**: semantic search + LLM generation with prompt assembly and guardrails
- **Graceful degradation**: fallback tiers (mock results, limited AI, guidance-only)
- **Observability**: structured logs, per-query IDs, latency/throughput counters, confidence scoring
- **Config-driven**: tweak ports, Qdrant/Ollama endpoints, timeouts via HOCON/YAML

---

## Tech Stack
- **Language/Runtime**: Java 17+, Akka Typed + Akka Cluster  
- **Vector DB**: Qdrant  
- **LLM Runtime**: Ollama (Microsoft **Phi-3 Mini**)  
- **Embeddings**: sentence-transformers (MiniLM family via DJL or client)  
- **Serialization**: Jackson CBOR (efficient, compact over the wire)  
- **Containers**: Docker / Docker Compose  
- **Build**: Maven or Gradle

---

## Quick Start

### 1) Prerequisites
- Java 17+
- Docker & Docker Compose
- `ollama` installed locally

### 2) Start Dependencies (Qdrant + Ollama)
```bash
# Qdrant (vector DB)
docker run -d --name qdrant -p 6333:6333 qdrant/qdrant:latest

# Ollama (LLM runtime)
ollama serve &
ollama pull phi3:mini
```

### 3) Index Policies
Put your policy `.txt` files under `data/policies/`, then:
```bash
# Converts policy text -> embeddings -> Qdrant vectors
./gradlew run --args="index"    # or: mvn exec:java -Dexec.args="index"
```

### 4) Build
```bash
./gradlew clean build  # or: mvn -q -DskipTests package
```

---

## Configuration

Create `conf/application.conf` (HOCON):

```hocon
app {
  qdrant {
    url = "http://127.0.0.1:6333"
    collection = "policies"
    topK = 5
  }

  ollama {
    url = "http://127.0.0.1:11434"
    model = "phi3:mini"
    timeoutSec = 70
  }

  embeddings {
    model = "sentence-transformers/all-MiniLM-L6-v2"
  }

  frontend {
    port = 2553
  }
  backend {
    port = 2552
  }
  seed {
    port = 2551
  }
}
```

Environment variables (optional overrides):
```bash
export QDRANT_URL=http://127.0.0.1:6333
export OLLAMA_URL=http://127.0.0.1:11434
export OLLAMA_MODEL=phi3:mini
```

---

## Run the Cluster

In three terminals (order matters):

```bash
# 1) Seed node
./gradlew run --args="seed"     # port 2551

# 2) Backend node
./gradlew run --args="backend"  # port 2552

# 3) Frontend node (interactive console)
./gradlew run --args="frontend" # port 2553
```

Example interactive session (frontend):
```
🤖 Policy Assistant ready. Type your question (or 'exit'):

> housing policy
[ASK] search backend … [OK]
[ASK] LLM generation … [OK]
Confidence: 0.89 | Latency: 4.2s
Answer:
… concise guidance with referenced policy excerpts …
```

---

## Observability & Ops

**Logging**
- Per-request `queryId`
- Millisecond timestamps
- Node role/host in each log line
- Forwarded messages recorded for audit trails

**Metrics (suggested)**
- `search_latency_ms`, `llm_latency_ms`, `end_to_end_ms`
- `cluster_members`, `backend_busy`, `queries_per_min`
- `confidence_score` histogram

**Health**
- Startup diagnostics (`DiagCheck`)
- Dependency checks (Qdrant/Ollama reachable)
- Circuit-breaker fallback to limited mode

---

## Testing

**Unit/Integration**
- Backend search: embedding + top-k similarity ranking
- LLM prompt builder: context assembly & truncation rules
- Actor flows: ask/tell/forward paths with adapters

**Command examples**
```bash
./gradlew test
# or
mvn -q -Dtest=*Test test
```

---

## Benchmarks

> Sample local runs on laptop (LLM local, 150+ policies):
- **Search latency**: 200–500 ms  
- **End-to-end (RAG)**: 3–6 s (query-dependent)  
- **Cluster formation**: < 5 s  
- **Service discovery**: < 50 ms  

> Throughput scales with backend nodes. LLM is typically the bottleneck—consider remote GPUs or distilled models for prod.

---

## Production Hardening

- **Scale-out**: multiple backend nodes; pin LLM service per node or centralize behind a gateway
- **Resilience**: retries, backoff, bulkhead limits for LLM and vector DB
- **Security**: network segmentation, auth between services, redact logs
- **Observability**: ship logs/metrics to ELK/CloudWatch/Prometheus + Grafana
- **Policy updates**: background re-index pipeline + versioned collections
- **Prompt safety**: length caps, function-calling skeletons, model fallbacks
- **Data privacy**: strip PII in prompts; encrypt transit and at rest

---

## Troubleshooting

- **Ollama connection refused**  
  Ensure `ollama serve` is running and `OLLAMA_URL` is correct.

- **No search results**  
  Verify you ran the indexer and `app.qdrant.collection` exists.

- **Cluster won’t form**  
  Check seed address/port; ensure ports 2551–2553 are free and not blocked by firewall/VPN.

- **Slow responses**  
  Reduce `topK`, trim prompt context, or use a faster LLM / remote inference.


---

### Appendix: Typical Module Layout
```
/src/main/java/...
  app/PolicyClusterApp.java
  frontend/PolicyFrontend.java
  backend/PolicyBackendWorker.java
  backend/PolicyLLMService.java
  infra/PolicyLogger.java
  infra/PolicyClusterListener.java
  rag/EmbeddingService.java
  rag/QdrantClient.java
  rag/PromptBuilder.java
  indexer/PolicyIndexer.java
```
