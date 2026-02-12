# Decision Engine — Technical Architecture (v2)

## Overview

A long-lived, stateful decision engine built on Drools with:
- Complex Event Processing (temporal reasoning, sliding windows)
- Replayable changesets as the sole write path
- PostgreSQL-backed checkpoints with deterministic replay
- Full-depth fact provenance with human-readable explanations
- REST API for all consumers (services, dashboards, third parties, CLI)
- Zero-downtime rule hot swap via DRL upload
- Docker/Kubernetes deployment

---

## Design Drivers

| Dimension | Decision | Implication |
|-----------|----------|-------------|
| Working memory | 100K–1M facts | Incremental checkpointing; selective provenance persistence |
| Changeset ordering | Strictly ordered, single writer | Linear log; deterministic replay; no conflict resolution needed |
| Rule upgrades | Hot swap, zero downtime | Transfer base facts to new session; TMS re-derives conclusions |
| CEP | Yes — temporal operators, sliding windows | STREAM mode; pseudo clock for replay; events vs facts distinction |
| Provenance | Full derivation tree | Agenda + runtime listener correlation; async DB persistence |
| API consumers | Services, dashboard, third parties, CLI | OpenAPI spec; API key auth; structured errors; SSE for dashboard |
| Multi-tenancy | Single tenant | One session per instance; extend later via multiple instances |
| Deployment | Docker / Kubernetes | Health/readiness probes; graceful shutdown; resource limits |

---

## Technology Stack

| Component | Technology | Rationale |
|-----------|-----------|-----------|
| **Runtime** | Java 21 (virtual threads) | Best Drools compatibility; virtual threads for cheap REST concurrency |
| **Framework** | Quarkus 3.x | Fastest startup, lowest footprint, native Drools/KIE ecosystem affinity, excellent K8s support, dev mode with live reload |
| **Build** | Gradle (Kotlin DSL) | Fast incremental builds, `--continuous` for test-driven DRL development |
| **Rule engine** | Drools 8.x (latest) | PHREAK algorithm, TMS, CEP/Fusion, temporal operators |
| **Database** | PostgreSQL 16 | JSONB for flexible storage, BYTEA for checkpoint blobs, robust transactions |
| **DB access** | jOOQ 3.19 | Type-safe SQL, no ORM magic, explicit queries, lightweight |
| **Migrations** | Flyway | Standard, Quarkus-integrated |
| **REST** | Quarkus RESTEasy Reactive | Non-blocking endpoints, SSE built in, OpenAPI generation |
| **Auth** | API keys (simple) → extensible to OAuth2/OIDC | Third-party access requires auth; internal services use shared keys initially |
| **Serialization** | Jackson (API), MessagePack (checkpoints) | JSON for humans, compact binary for storage |
| **Compression** | LZ4 | Fast compression for checkpoint blobs |
| **Containerization** | Docker multi-stage build | Small image, reproducible builds |
| **Testing** | JUnit 5 + Quarkus Test + Testcontainers | Real PostgreSQL in tests, no mocks |
| **Observability** | Micrometer → Prometheus + Quarkus Health | K8s-native metrics and probes |
| **API docs** | SmallRye OpenAPI (Swagger UI) | Auto-generated spec; clients can codegen |

---

## The Fact vs Event Distinction

CEP introduces a fundamental split in working memory. This distinction ripples through
every component — changesets, checkpoints, replay, provenance, and hot swap.

```
Working Memory
├── BASE FACTS (eternal until explicitly changed)
│   ├── Inserted by changesets
│   ├── Tracked in FactRegistry by stable key
│   ├── Serialized in checkpoints
│   ├── Transferred during hot swap
│   └── Examples: Customer, Account, Policy, Configuration
│
├── EVENTS (temporal, auto-expiring)
│   ├── Inserted by changesets via entry points
│   ├── Annotated with @role(event), @timestamp, @expires in DRL
│   ├── Participate in temporal operators (after, before, during, etc.)
│   ├── Participate in sliding windows (over window:time, window:length)
│   ├── Auto-garbage-collected after expiration
│   ├── NOT in checkpoints (replayed from changeset log within replay window)
│   ├── NOT transferred during hot swap (replayed from log)
│   └── Examples: Transaction, SensorReading, LoginAttempt, PriceUpdate
│
└── DERIVED FACTS (TMS-managed conclusions)
    ├── Inserted by rules via insertLogical()
    ├── Auto-retract when justification lost
    ├── NOT in checkpoints (re-derived on restore)
    ├── NOT transferred during hot swap (re-derived by new rules)
    ├── Have rich provenance (full derivation tree)
    └── Examples: RiskAssessment, FraudAlert, ComplianceViolation
```

**Rules must follow these conventions:**
- `insert()` — NEVER used in rule RHS (only the changeset processor inserts base facts)
- `insertLogical()` — ALWAYS used for rule-derived conclusions
- Events declared with `@role(event)` in DRL — engine enforces temporal semantics

This three-way split is the architectural backbone. Every component asks:
"Is this a base fact, an event, or a derived fact?" and behaves accordingly.

---

## Project Structure

```
decision-engine/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/
│   └── libs.versions.toml
├── Dockerfile
├── k8s/
│   ├── deployment.yaml
│   ├── service.yaml
│   ├── configmap.yaml
│   └── hpa.yaml
│
├── src/main/
│   ├── java/com/example/engine/
│   │   ├── DecisionEngineApp.java
│   │   │
│   │   ├── core/
│   │   │   ├── EngineSession.java               # Long-lived session lifecycle
│   │   │   ├── ChangesetProcessor.java           # Applies changesets to session
│   │   │   ├── RuleCompiler.java                 # Compiles DRL → KieBase
│   │   │   ├── RuleHotSwapper.java               # Zero-downtime rule upgrades
│   │   │   ├── FactRegistry.java                 # factKey → FactHandle for base facts
│   │   │   ├── EventReplayWindow.java            # Tracks replay window for events
│   │   │   ├── SessionLock.java                  # Single-writer enforcement
│   │   │   └── ClockManager.java                 # Pseudo clock management for replay
│   │   │
│   │   ├── changeset/
│   │   │   ├── Changeset.java                    # Immutable changeset record
│   │   │   ├── ChangesetEntry.java               # Single operation (fact or event)
│   │   │   ├── EntryKind.java                    # FACT or EVENT
│   │   │   ├── ChangesetAction.java              # UPSERT, DELETE (facts); EMIT (events)
│   │   │   ├── ChangesetLog.java                 # Append-only PostgreSQL store
│   │   │   ├── ChangesetReplayer.java            # Replay from checkpoint
│   │   │   └── ChangesetValidator.java           # Schema validation before apply
│   │   │
│   │   ├── checkpoint/
│   │   │   ├── CheckpointManager.java            # Periodic + on-demand snapshots
│   │   │   ├── CheckpointStore.java              # PostgreSQL persistence
│   │   │   ├── FactSerializer.java               # MessagePack + LZ4
│   │   │   └── CheckpointRecovery.java           # Restore + replay on startup
│   │   │
│   │   ├── provenance/
│   │   │   ├── ProvenanceTracker.java            # In-memory derivation graph
│   │   │   ├── ProvenanceAgendaListener.java     # Captures firing context
│   │   │   ├── ProvenanceRuntimeListener.java    # Captures fact changes
│   │   │   ├── ProvenanceStore.java              # Async PostgreSQL persistence
│   │   │   ├── DerivationTree.java               # Tree structure
│   │   │   ├── Explainer.java                    # Human-readable explanations
│   │   │   └── ProvenanceExporter.java           # DOT/JSON graph export
│   │   │
│   │   ├── api/
│   │   │   ├── ChangesetResource.java            # POST /changesets
│   │   │   ├── FactResource.java                 # GET /facts/**
│   │   │   ├── QueryResource.java                # POST /queries/{name}
│   │   │   ├── ProvenanceResource.java           # GET /provenance/**
│   │   │   ├── RuleResource.java                 # POST /rules, GET /rules/**
│   │   │   ├── CheckpointResource.java           # POST/GET /checkpoints
│   │   │   ├── EventStreamResource.java          # GET /stream (SSE)
│   │   │   ├── HealthResource.java               # Readiness/liveness
│   │   │   ├── ApiKeyFilter.java                 # Auth filter
│   │   │   ├── ErrorMapper.java                  # Structured error responses
│   │   │   └── dto/
│   │   │       ├── ChangesetRequest.java
│   │   │       ├── ChangesetResponse.java
│   │   │       ├── FactResponse.java
│   │   │       ├── EventEntry.java
│   │   │       ├── ProvenanceResponse.java
│   │   │       ├── ExplainResponse.java
│   │   │       ├── RuleUploadRequest.java
│   │   │       ├── RuleUploadResponse.java
│   │   │       ├── QueryRequest.java
│   │   │       ├── QueryResponse.java
│   │   │       ├── HealthResponse.java
│   │   │       └── ErrorResponse.java
│   │   │
│   │   └── config/
│   │       ├── EngineConfig.java                 # Typed config (@ConfigMapping)
│   │       └── JooqProducer.java                 # jOOQ DSLContext CDI producer
│   │
│   └── resources/
│       ├── application.properties
│       ├── db/migration/
│       │   ├── V001__changeset_log.sql
│       │   ├── V002__checkpoints.sql
│       │   ├── V003__provenance.sql
│       │   ├── V004__rule_versions.sql
│       │   └── V005__api_keys.sql
│       └── rules/
│           └── bootstrap-rules.drl
│
├── src/test/
│   ├── java/com/example/engine/
│   │   ├── core/
│   │   │   ├── ChangesetProcessorTest.java
│   │   │   ├── RuleHotSwapperTest.java
│   │   │   ├── EngineSessionTest.java
│   │   │   └── EventExpirationTest.java
│   │   ├── changeset/
│   │   │   ├── ChangesetReplayerTest.java
│   │   │   └── ChangesetIdempotencyTest.java
│   │   ├── checkpoint/
│   │   │   ├── CheckpointRoundTripTest.java
│   │   │   └── CheckpointWithEventsTest.java
│   │   ├── provenance/
│   │   │   ├── ProvenanceTrackerTest.java
│   │   │   ├── ExplainerTest.java
│   │   │   └── CepProvenanceTest.java
│   │   ├── api/
│   │   │   ├── ChangesetResourceTest.java
│   │   │   ├── RuleResourceTest.java
│   │   │   └── AuthFilterTest.java
│   │   └── scenarios/
│   │       ├── FullLifecycleTest.java
│   │       ├── HotSwapWithCepTest.java
│   │       ├── CrashRecoveryTest.java
│   │       └── EventWindowReplayTest.java
│   └── resources/
│       └── test-rules/
│           ├── simple-rules.drl
│           ├── cep-rules.drl
│           └── upgrade-v2-rules.drl
│
└── docs/
    └── architecture.md
```

---

## Component Architecture

```
                         External         Internal          Dashboard        CLI
                         Third Parties    Services          (SSE)
                             │               │                │              │
                             └──────┬────────┘                │              │
                                    │                         │              │
                             ┌──────▼──────┐                  │              │
                             │  API Key     │                  │              │
                             │  Auth Filter │                  │              │
                             └──────┬──────┘                  │              │
                                    │                         │              │
         ┌──────────┬───────────────┼──────────┬──────────────┼──────────────┤
         │          │               │          │              │              │
         ▼          ▼               ▼          ▼              ▼              ▼
  ┌──────────┐ ┌────────┐  ┌──────────┐ ┌──────────┐ ┌──────────┐  ┌──────────┐
  │Changeset │ │  Fact   │  │  Query   │ │  Rule    │ │SSE Stream│  │Provenance│
  │Resource  │ │Resource │  │ Resource │ │ Resource │ │ Resource │  │ Resource │
  └────┬─────┘ └────┬────┘  └────┬─────┘ └────┬─────┘ └────┬─────┘  └────┬─────┘
       │             │            │            │             │             │
       ▼             │            │            ▼             │             │
┌──────────────┐     │            │     ┌─────────────┐     │             │
│  Changeset   │     │            │     │  Rule Hot   │     │             │
│  Processor   │     │            │     │  Swapper    │     │             │
└──────┬───────┘     │            │     └──────┬──────┘     │             │
       │             │            │            │            │             │
       ▼             ▼            ▼            ▼            ▼             ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                           Engine Session                                     │
│                                                                              │
│ ┌──────────────────────────────────────────────────────────────────────────┐ │
│ │                 KieSession (long-lived, STREAM mode)                      │ │
│ │                                                                          │ │
│ │  ┌───────────────────┐ ┌──────────────────┐ ┌────────────────────────┐  │ │
│ │  │ Working Memory     │ │ Entry Points     │ │ Pseudo Clock           │  │ │
│ │  │ ├─ Base facts      │ │ ├─ DEFAULT       │ │ (real-time in prod,    │  │ │
│ │  │ ├─ Events          │ │ ├─ transactions  │ │  manual in replay)     │  │ │
│ │  │ └─ Derived (TMS)   │ │ ├─ sensors       │ └────────────────────────┘  │ │
│ │  └───────────────────┘ │ └─ (per DRL)      │                            │ │
│ │                        └──────────────────┘                            │ │
│ │  PHREAK Network ── Temporal Indexes ── Agenda ── fireUntilHalt()       │ │
│ └──────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
│ ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌──────────────────────────┐  │
│ │Session Lock│ │Fact        │ │Clock       │ │Provenance Tracker        │  │
│ │(single     │ │Registry    │ │Manager     │ │(agenda + runtime         │  │
│ │ writer)    │ │(base facts │ │(pseudo ↔   │ │ listener pair)           │  │
│ │            │ │ only)      │ │ real-time) │ │                          │  │
│ └────────────┘ └────────────┘ └────────────┘ └──────────────────────────┘  │
└────────────────────────────────┬─────────────────────────────────────────────┘
                                 │
         ┌───────────────────────┼───────────────────────────┐
         │                       │                           │
         ▼                       ▼                           ▼
  ┌──────────────┐     ┌──────────────────┐        ┌─────────────────┐
  │ Changeset    │     │ Checkpoint       │        │ Provenance      │
  │ Log          │     │ Store            │        │ Store           │
  │ (append-only)│     │ (periodic snaps) │        │ (async batched) │
  └──────┬───────┘     └────────┬─────────┘        └────────┬────────┘
         │                      │                           │
         ▼                      ▼                           ▼
  ┌──────────────────────────────────────────────────────────────────┐
  │                      PostgreSQL 16                                │
  │                                                                   │
  │ changeset_log   checkpoints   fact_provenance   rule_versions    │
  │ api_keys        fact_modifications                                │
  └──────────────────────────────────────────────────────────────────┘
```

---

## Database Schema

```sql
-- V001__changeset_log.sql

CREATE TABLE changeset_log (
    sequence_num    BIGSERIAL PRIMARY KEY,
    changeset_id    UUID NOT NULL UNIQUE,
    payload         JSONB NOT NULL,
    checksum        TEXT NOT NULL,
    submitted_by    TEXT,
    applied_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    engine_clock_at BIGINT NOT NULL,            -- pseudo clock millis at time of apply
    rules_fired     INTEGER,
    duration_ms     INTEGER,
    error           TEXT
);

CREATE INDEX idx_changeset_applied ON changeset_log(applied_at);
CREATE INDEX idx_changeset_clock ON changeset_log(engine_clock_at);


-- V002__checkpoints.sql

CREATE TABLE checkpoints (
    checkpoint_id     UUID PRIMARY KEY,
    sequence_num      BIGINT NOT NULL,
    rule_version_id   UUID NOT NULL,
    clock_millis      BIGINT NOT NULL,
    fact_count        INTEGER NOT NULL,
    blob_format       TEXT NOT NULL DEFAULT 'msgpack+lz4',
    fact_blob         BYTEA NOT NULL,
    fact_registry     BYTEA NOT NULL,
    engine_metadata   JSONB NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    size_bytes        BIGINT NOT NULL
);

CREATE INDEX idx_checkpoint_seq ON checkpoints(sequence_num DESC);


-- V003__provenance.sql

CREATE TABLE fact_provenance (
    fact_id            TEXT PRIMARY KEY,
    fact_type          TEXT NOT NULL,
    fact_key           TEXT,
    produced_by_rule   TEXT,
    insertion_type     TEXT NOT NULL,
    input_fact_ids     TEXT[] NOT NULL DEFAULT '{}',
    changeset_id       UUID,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    retracted_at       TIMESTAMPTZ,
    retraction_reason  TEXT
);

CREATE INDEX idx_prov_type ON fact_provenance(fact_type);
CREATE INDEX idx_prov_rule ON fact_provenance(produced_by_rule)
    WHERE produced_by_rule IS NOT NULL;
CREATE INDEX idx_prov_key ON fact_provenance(fact_key)
    WHERE fact_key IS NOT NULL;

CREATE TABLE fact_modifications (
    id                  BIGSERIAL PRIMARY KEY,
    fact_id             TEXT NOT NULL REFERENCES fact_provenance(fact_id),
    rule_name           TEXT NOT NULL,
    modified_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    before_state        JSONB,
    after_state         JSONB,
    triggering_facts    TEXT[] NOT NULL DEFAULT '{}'
);

CREATE INDEX idx_mod_fact ON fact_modifications(fact_id);


-- V004__rule_versions.sql

CREATE TABLE rule_versions (
    version_id       UUID PRIMARY KEY,
    version_label    TEXT NOT NULL,
    drl_files        JSONB NOT NULL,
    checksum         TEXT NOT NULL,
    uploaded_by      TEXT,
    uploaded_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    activated_at     TIMESTAMPTZ,
    deactivated_at   TIMESTAMPTZ,
    is_active        BOOLEAN NOT NULL DEFAULT false,
    compilation_log  TEXT
);

CREATE UNIQUE INDEX idx_rule_active
    ON rule_versions(is_active) WHERE is_active = true;


-- V005__api_keys.sql

CREATE TABLE api_keys (
    key_id          UUID PRIMARY KEY,
    key_hash        TEXT NOT NULL UNIQUE,
    name            TEXT NOT NULL,
    permissions     TEXT[] NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ,
    is_active       BOOLEAN NOT NULL DEFAULT true,
    last_used_at    TIMESTAMPTZ
);
```

---

## Changeset Model

### Request Format

```json
{
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "entries": [
        {
            "kind": "FACT",
            "action": "UPSERT",
            "factKey": "customer:C-001",
            "factType": "Customer",
            "data": {
                "id": "C-001",
                "name": "Alice",
                "creditScore": 720,
                "region": "US"
            }
        },
        {
            "kind": "FACT",
            "action": "DELETE",
            "factKey": "customer:C-099"
        },
        {
            "kind": "EVENT",
            "action": "EMIT",
            "entryPoint": "transactions",
            "factType": "Transaction",
            "timestamp": "2026-02-12T14:30:00Z",
            "data": {
                "txId": "TX-001",
                "customerId": "C-001",
                "amount": 15000.00,
                "merchantCategory": "ELECTRONICS"
            }
        }
    ],
    "metadata": {
        "source": "crm-service",
        "correlationId": "req-abc-123"
    }
}
```

### Response Format

```json
{
    "changesetId": "550e8400-e29b-41d4-a716-446655440000",
    "sequenceNum": 42,
    "status": "APPLIED",
    "rulesFired": 7,
    "durationMs": 23,
    "effects": {
        "factsInserted": 1,
        "factsUpdated": 0,
        "factsDeleted": 1,
        "eventsEmitted": 1,
        "derivedFactsCreated": 3,
        "derivedFactsRetracted": 1
    },
    "newDerivedFacts": [
        {
            "factId": "RiskFlag:C-001:HIGH_VALUE",
            "factType": "RiskFlag",
            "producedByRule": "Flag High Value Transaction",
            "summary": "HIGH_VALUE flag for customer C-001"
        }
    ]
}
```

### Design Decisions

- **`kind: FACT` vs `kind: EVENT`** — Explicitly distinguishes persistent facts from temporal events.
- **`UPSERT` for facts** — Insert if key doesn't exist, update if it does. Idempotent on replay.
- **`EMIT` for events** — Events are append-only; never updated or deleted. They expire via `@expires`.
- **`timestamp` on events** — The caller provides the business timestamp. During replay, the pseudo clock advances to this timestamp.
- **Idempotency** — UUID deduplication via `UNIQUE` constraint. Resubmitting is a no-op.
- **Atomicity** — All entries succeed or none do. Applied within a single session lock acquisition.

---

## CEP: Clock Management and Event Replay

### Dual Clock Mode

**Production mode (real-time):**
```
Events arrive → engine clock = wall clock
Temporal operators evaluate against real time
@expires triggers garbage collection in real time
Sliding windows slide with wall clock
```

**Replay mode (pseudo clock):**
```
Checkpoint restored → clock set to checkpoint's clock_millis
Changesets replayed in order:
    For each changeset:
        If changeset contains events:
            Advance pseudo clock to event's timestamp
        Apply changeset entries
        fireAllRules()
After replay complete → switch to real-time clock
```

### The Event Replay Window

Events expire via `@expires`. Checkpoints don't store events (they'd be stale).
Events are replayed from the changeset log within the **maximum temporal window**.

```
max_replay_window = max(@expires across all event types,
                        max temporal window in any rule)

On recovery:
    1. Load checkpoint (base facts + clock state)
    2. Query changeset_log WHERE engine_clock_at > (checkpoint_clock - max_replay_window)
    3. Replay those changesets using pseudo clock
    4. Switch to real-time clock
```

### Event Entry Points

```
DRL declaration:
    declare Transaction
        @role(event)
        @timestamp(occurredAt)
        @expires(1h)
    end

Changeset entry:
    { "kind": "EVENT", "entryPoint": "transactions", ... }

Engine:
    ksession.getEntryPoint("transactions").insert(event)
```

---

## Checkpoint Strategy

### What Gets Checkpointed

| Category | Checkpointed? | Why |
|----------|---------------|-----|
| Base facts | Yes | Cannot be re-derived |
| Events | No | Replayed from changeset log within replay window |
| Derived facts | No | Re-derived by TMS after restore + fireAllRules |
| Pseudo clock state | Yes | Resume correct temporal position |
| FactRegistry | Yes | Rebuild factKey → handle mappings |
| Active rule version ID | Yes | Restore with correct rules |

### Checkpoint Timing

Triggered by (whichever comes first):
- Every N changesets (default: 100)
- Every M minutes (default: 5)
- On demand via `POST /checkpoints`
- Automatically before rule hot swap

### Serialization Budget (1M base facts)

- Raw MessagePack: ~200 MB
- LZ4 compressed: ~40-60 MB
- PostgreSQL write: ~1-2 seconds
- Restore (deserialize + insert): ~5-10 seconds
- Event replay: depends on window size and event volume
- **Total cold start: 10-30 seconds**

---

## Rule Hot Swap — Zero Downtime with CEP

### Hot Swap Procedure

```
POST /rules { "files": { "rules.drl": "..." } }
    │
    ▼
 1. Compile new DRL with KieHelper
    - EventProcessingOption.STREAM, EqualityBehaviorOption.EQUALITY
    - Fail → 400 with compilation errors
    │
    ▼
 2. Validate fact type compatibility
    - All factTypes in FactRegistry must exist in new KieBase
    - All event types with pending events must exist
    - Incompatible → 409 with details
    │
    ▼
 3. Acquire session lock (blocks new changesets)
    │
    ▼
 4. Take pre-swap checkpoint (rollback safety net)
    │
    ▼
 5. Create new KieSession (STREAM mode, pseudo clock)
    │
    ▼
 6. Transfer base facts from FactRegistry
    For each (factKey, factType, fieldData):
        - Create instance via new KieBase.getFactType()
        - Populate fields
        - Insert into new session
        - Update FactRegistry with new FactHandle
    │
    ▼
 7. Replay recent events from changeset log
    - Events within max_replay_window
    - Advance pseudo clock to each event timestamp
    - Insert into appropriate entry points
    │
    ▼
 8. Wire provenance listeners to new session
    │
    ▼
 9. fireAllRules() — TMS derives conclusions under new rules
    │
    ▼
10. Switch pseudo clock → real-time
    │
    ▼
11. Atomically swap session reference
    │
    ▼
12. Dispose old session
    │
    ▼
13. Persist new rule version, deactivate previous
    │
    ▼
14. Release session lock
    │
    ▼
15. Return swap result with delta summary
```

**Rollback:** If any step after checkpoint fails, restore from pre-swap checkpoint.

---

## REST API Surface

```
Auth: All endpoints require X-Api-Key header.

CHANGESETS
  POST   /api/v1/changesets                    Apply a changeset
  GET    /api/v1/changesets                    List (paginated)
  GET    /api/v1/changesets/{id}               Detail + result

FACTS
  GET    /api/v1/facts                         List base facts (paginated, ?type=&limit=)
  GET    /api/v1/facts/{factKey}               Get by key
  GET    /api/v1/facts/types                   All fact types in memory
  GET    /api/v1/facts/stats                   Counts by type, memory estimate

DERIVED FACTS
  GET    /api/v1/derived                       List (paginated, ?type=)
  GET    /api/v1/derived/{factId}              Get by ID

EVENTS
  GET    /api/v1/events                        Currently in memory (not expired)
  GET    /api/v1/events/entry-points           Available entry points

QUERIES
  POST   /api/v1/queries/{queryName}           Execute DRL query
  GET    /api/v1/queries                       List available queries

PROVENANCE
  GET    /api/v1/provenance/{factId}           Provenance record
  GET    /api/v1/provenance/{factId}/tree      Full derivation tree
  GET    /api/v1/provenance/{factId}/explain   Human-readable explanation
  GET    /api/v1/provenance/{factId}/graph     DOT or JSON (?format=dot|json)
  GET    /api/v1/provenance/{factId}/impact    Impact analysis
  GET    /api/v1/provenance/by-rule/{name}     Facts produced by rule

RULES
  POST   /api/v1/rules                         Upload + activate (hot swap)
  POST   /api/v1/rules/validate                Validate without activating
  GET    /api/v1/rules/current                 Active version + DRL content
  GET    /api/v1/rules/versions                All versions
  POST   /api/v1/rules/rollback/{versionId}    Rollback to previous version

CHECKPOINTS
  POST   /api/v1/checkpoints                   Trigger checkpoint
  GET    /api/v1/checkpoints                   List
  GET    /api/v1/checkpoints/latest             Latest metadata

OPERATIONS
  GET    /api/v1/health                         K8s readiness
  GET    /api/v1/health/live                    K8s liveness
  GET    /api/v1/metrics                        Prometheus

STREAMING
  GET    /api/v1/stream                         SSE: all changes
  GET    /api/v1/stream/derived                 SSE: derived facts only
  GET    /api/v1/stream/derived/{type}          SSE: by derived fact type
```

### Error Response Format

```json
{
    "error": {
        "code": "CHANGESET_VALIDATION_FAILED",
        "message": "Unknown fact type 'Custmer' in entry 0. Did you mean 'Customer'?",
        "details": {
            "entryIndex": 0,
            "factType": "Custmer",
            "availableTypes": ["Customer", "Order", "Account"]
        },
        "timestamp": "2026-02-12T14:30:00Z",
        "requestId": "req-abc-123"
    }
}
```

### Authentication & Permissions

```
Permissions:
  CHANGESET_WRITE    POST /changesets
  FACT_READ          GET /facts/**, /derived/**, /events/**
  QUERY_EXECUTE      POST /queries/**
  PROVENANCE_READ    GET /provenance/**
  RULE_READ          GET /rules/**
  RULE_ADMIN         POST /rules, POST /rules/rollback
  CHECKPOINT_READ    GET /checkpoints
  CHECKPOINT_WRITE   POST /checkpoints
  STREAM_READ        GET /stream/**
  HEALTH_READ        GET /health, /metrics

Typical assignments:
  crm-service:     [CHANGESET_WRITE, FACT_READ]
  dashboard:       [FACT_READ, PROVENANCE_READ, STREAM_READ, QUERY_EXECUTE]
  rule-admin-cli:  [RULE_ADMIN, RULE_READ, CHECKPOINT_WRITE, CHECKPOINT_READ]
  external-api:    [FACT_READ, QUERY_EXECUTE, PROVENANCE_READ]
```

---

## Provenance & Explanation

### CEP-Aware Explanation Example

```json
{
    "factId": "FraudAlert:C-001:VELOCITY",
    "factType": "FraudAlert",
    "explanation": [
        "A VELOCITY fraud alert was raised for customer C-001 because:",
        "",
        "1. Rule 'Calculate Transaction Velocity' detected 8 transactions",
        "   totaling $40,000 within a 30-minute sliding window, exceeding",
        "   the threshold of 5 transactions / $20,000.",
        "",
        "   Contributing events (most recent first):",
        "   - TX-008 ($5,000) at 14:28:00 via 'transactions' entry point",
        "   - TX-007 ($5,000) at 14:26:00",
        "   - TX-006 ($5,000) at 14:24:00",
        "   - ... and 5 more within the window",
        "",
        "2. Rule 'Flag Velocity Anomaly' created this alert with risk score 0.8.",
        "",
        "This is a logically-asserted fact. It will auto-retract when",
        "transaction velocity drops below threshold."
    ],
    "derivationTree": { "..." }
}
```

### Performance at Scale

- In-memory graph: derived facts only (1-10% of total) → 10-100MB
- Async persistence: bounded queue (10K), batch flush (500 entries)
- Explanation: on-demand graph traversal, never blocks rules
- Base facts have trivial provenance ("changeset X") — cheap

---

## Kubernetes Deployment

### Dockerfile

```dockerfile
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY gradle/ gradle/
COPY gradlew build.gradle.kts settings.gradle.kts ./
RUN ./gradlew dependencies --no-daemon
COPY src/ src/
RUN ./gradlew build -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S engine && adduser -S engine -G engine
USER engine
COPY --from=build /app/build/quarkus-app/ ./
ENV JAVA_OPTS="-Xmx3g -XX:+UseZGC -XX:+ZGenerational"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]
```

### Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: decision-engine
spec:
  replicas: 1                             # Single instance — stateful
  strategy:
    type: Recreate                        # No rolling — one instance at a time
  selector:
    matchLabels:
      app: decision-engine
  template:
    metadata:
      labels:
        app: decision-engine
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/path: /api/v1/metrics
        prometheus.io/port: "8080"
    spec:
      terminationGracePeriodSeconds: 60
      containers:
        - name: engine
          image: decision-engine:latest
          ports:
            - containerPort: 8080
          envFrom:
            - secretRef:
                name: decision-engine-db
          resources:
            requests:
              memory: "2Gi"
              cpu: "1"
            limits:
              memory: "4Gi"
              cpu: "4"
          livenessProbe:
            httpGet:
              path: /api/v1/health/live
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /api/v1/health
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 5
          lifecycle:
            preStop:
              httpGet:
                path: /api/v1/internal/shutdown
                port: 8080
```

### Graceful Shutdown

```
SIGTERM received
    │
    ▼
 1. Set state = DRAINING (new changesets → 503)
 2. SSE streams receive "shutting-down" event
 3. Wait for in-flight changeset to complete
 4. Take final checkpoint
 5. Flush provenance queue
 6. ksession.halt() → ksession.dispose()
 7. Close DB connections
 8. Exit
```

---

## Recovery Sequence

```
Engine starts
    │
    ▼
Flyway migrations
    │
    ▼
Load active rule version → compile DRL → KieBase (STREAM, EQUALITY, pseudo clock)
    │
    ▼
Create KieSession + wire provenance listeners
    │
    ▼
Load latest checkpoint
    ├── Deserialize base facts → insert
    ├── Rebuild FactRegistry
    └── Set pseudo clock to checkpoint's clock_millis
    │
    ▼
Calculate replay_from = checkpoint_clock - max_replay_window
    │
    ▼
Replay changesets (sequence_num > checkpoint AND clock >= replay_from)
    For each: advance clock if events, apply entries, fireAllRules()
    │
    ▼
Switch pseudo clock → real-time
    │
    ▼
Start fireUntilHalt() on background thread
    │
    ▼
State = RUNNING → accept changesets
Start checkpoint scheduler
Start provenance flush scheduler
```

---

## Memory Budget (1M base facts)

| Component | Estimate |
|-----------|----------|
| Base facts in working memory | ~500 MB |
| PHREAK network + temporal indexes | ~200 MB |
| Events in memory (~50K active) | ~50 MB |
| Derived facts (~100K) | ~50 MB |
| FactRegistry overhead | ~50 MB |
| Provenance graph (derived only) | ~50-100 MB |
| JVM + GC headroom | ~500 MB |
| **Total** | **~1.5-2 GB** |

JVM: `-Xmx3g -XX:+UseZGC -XX:+ZGenerational`

---

## Observability (Prometheus Metrics)

```
engine_fact_count{category="base|derived|event"}
engine_changeset_applied_total
engine_changeset_duration_seconds (histogram)
engine_changeset_entries_per_changeset (summary)
engine_rules_fired_total
engine_rules_fired_per_changeset (summary)
engine_checkpoint_total
engine_checkpoint_duration_seconds (histogram)
engine_checkpoint_size_bytes (gauge)
engine_provenance_queue_size (gauge)
engine_provenance_persisted_total
engine_events_expired_total
engine_temporal_window_max_seconds (gauge)
engine_session_uptime_seconds (gauge)
engine_session_memory_bytes (gauge)
engine_rule_version_active (info)
engine_hot_swap_total
engine_hot_swap_duration_seconds (histogram)
engine_recovery_duration_seconds (gauge)
```

---

## Key Invariants

1. **Changesets are the only write path.** No direct fact manipulation.
2. **Rules use `insertLogical()` exclusively.** `insert()` in RHS is a bug.
3. **Checkpoint + replay = identical state.** Deterministic recovery.
4. **Events are never checkpointed.** Replayed within temporal window.
5. **Hot swap preserves base facts, re-derives everything else.**
6. **Provenance is non-blocking.** Async persistence; never stalls rules.
7. **Single writer.** One changeset at a time. Session lock guarantees ordering.

---

## Development Workflow

```
Daily loop:

  1. Edit DRL in src/main/resources/rules/
  2. gradle test --continuous --tests "MyRulesTest"
  3. gradle quarkusDev → live reload + Swagger UI
  4. Test via curl/httpie/Swagger
  5. Hot swap rules via POST /api/v1/rules

Integration testing:
  Testcontainers (PostgreSQL)
  Full lifecycle: bootstrap → changesets → checkpoint → kill → recover → verify
```

---

## Implementation Phases

### Phase 0: Project Skeleton
**Goal:** Buildable, runnable, empty Quarkus app with all tooling wired.

- [ ] Initialize Gradle project with Kotlin DSL, `libs.versions.toml`
- [ ] Add Quarkus BOM, Drools 8.x, jOOQ, Flyway, Jackson, LZ4, MessagePack dependencies
- [ ] `DecisionEngineApp.java` — bare Quarkus main class
- [ ] `application.properties` — dev profile with PostgreSQL, Flyway, logging
- [ ] `docker-compose.yml` — PostgreSQL 16 for local dev
- [ ] Flyway migrations: `V001` through `V005` (all five tables)
- [ ] `JooqProducer.java` — CDI producer for `DSLContext`
- [ ] Verify: `gradle quarkusDev` starts, Flyway runs, `/q/health` responds

**Test:** App starts, schema exists, jOOQ can query `changeset_log`.

---

### Phase 1: Engine Core — Session + Changeset Processing
**Goal:** Accept a changeset via REST, insert base facts into a live KieSession, fire rules, return results.

- [ ] `EngineConfig.java` — typed config (`@ConfigMapping`) for engine settings
- [ ] `RuleCompiler.java` — compile DRL string → `KieBase` (STREAM mode, EQUALITY, pseudo clock)
- [ ] `EngineSession.java` — lifecycle: create KieSession, wire clock, `fireUntilHalt()` on background thread, `halt()`/`dispose()`
- [ ] `SessionLock.java` — `ReentrantLock`-based single-writer guard
- [ ] `FactRegistry.java` — `Map<String, FactHandle>` for base facts, keyed by `factKey`
- [ ] `Changeset.java`, `ChangesetEntry.java`, `EntryKind.java`, `ChangesetAction.java` — immutable records
- [ ] `ChangesetValidator.java` — validate structure, fact types against KieBase
- [ ] `ChangesetProcessor.java` — acquire lock → validate → apply UPSERT/DELETE → `fireAllRules()` → return effects
- [ ] `ChangesetLog.java` — append to `changeset_log` table via jOOQ
- [ ] `ChangesetResource.java` — `POST /api/v1/changesets`, `GET /api/v1/changesets`, `GET /api/v1/changesets/{id}`
- [ ] `FactResource.java` — `GET /api/v1/facts`, `GET /api/v1/facts/{factKey}`, `/types`, `/stats`
- [ ] DTOs: `ChangesetRequest`, `ChangesetResponse`, `FactResponse`, `ErrorResponse`
- [ ] `ErrorMapper.java` — structured error responses
- [ ] Bootstrap DRL: `bootstrap-rules.drl` with a simple test rule using `insertLogical()`
- [ ] `EngineSessionTest.java` — session starts, accepts facts, fires rules
- [ ] `ChangesetProcessorTest.java` — UPSERT inserts, re-UPSERT updates, DELETE removes, effects counted

**Test:** `curl POST /changesets` with a fact → rule fires → derived fact appears in response.

---

### Phase 2: Checkpoints + Recovery
**Goal:** Serialize base facts to PostgreSQL, restore from checkpoint on startup.

- [ ] `FactSerializer.java` — serialize/deserialize base facts via MessagePack + LZ4
- [ ] `CheckpointStore.java` — write/read `checkpoints` table (BYTEA blob + metadata)
- [ ] `CheckpointManager.java` — triggered every N changesets or M minutes; serializes base facts + FactRegistry + clock state
- [ ] `CheckpointRecovery.java` — on startup: load latest checkpoint → deserialize base facts → rebuild FactRegistry → set clock
- [ ] `ChangesetReplayer.java` — replay changesets after checkpoint's `sequence_num` → apply each → `fireAllRules()`
- [ ] `CheckpointResource.java` — `POST /api/v1/checkpoints`, `GET /api/v1/checkpoints`, `GET /api/v1/checkpoints/latest`
- [ ] Update `EngineSession` startup: run recovery sequence (checkpoint → replay → `fireUntilHalt()`)
- [ ] `CheckpointRoundTripTest.java` — checkpoint → dispose → restore → verify all base facts present
- [ ] `CrashRecoveryTest.java` — insert facts → checkpoint → apply more changesets → simulate restart → verify full state

**Test:** Kill and restart the engine; state is identical to before the kill.

---

### Phase 3: CEP — Events + Temporal Reasoning
**Goal:** Support event entry points, temporal operators, sliding windows, and pseudo clock.

- [ ] `ClockManager.java` — manage pseudo clock: advance to timestamp, switch pseudo ↔ real-time
- [ ] Update `ChangesetProcessor` — handle `kind: EVENT` / `action: EMIT`: insert via `ksession.getEntryPoint(name)`, advance clock to event timestamp
- [ ] `EventReplayWindow.java` — calculate `max_replay_window` from DRL `@expires` and temporal rule windows
- [ ] Update `ChangesetReplayer` — during replay, use pseudo clock; advance clock per event timestamp; after replay switch to real-time
- [ ] Update `CheckpointRecovery` — calculate `replay_from = checkpoint_clock - max_replay_window`; query changeset_log for events within window
- [ ] Events API: `GET /api/v1/events` (in-memory, not expired), `GET /api/v1/events/entry-points`
- [ ] Test DRL: `cep-rules.drl` — event declaration with `@role(event)`, `@timestamp`, `@expires`, sliding window rule
- [ ] `EventExpirationTest.java` — insert events → advance clock past `@expires` → verify events garbage-collected
- [ ] `CheckpointWithEventsTest.java` — checkpoint doesn't contain events; events replayed from log on restore
- [ ] `EventWindowReplayTest.java` — events within replay window are re-inserted; events outside are not

**Test:** Submit events → temporal rule fires within window → advance clock → event expires → derived fact auto-retracts via TMS.

---

### Phase 4: Provenance + Explanations
**Goal:** Track why every derived fact exists, produce human-readable explanations.

- [ ] `ProvenanceAgendaListener.java` — on `afterMatchFired`: capture rule name, matched facts, salience
- [ ] `ProvenanceRuntimeListener.java` — on `objectInserted`/`objectRetracted`: capture fact lifecycle, correlate with firing context
- [ ] `ProvenanceTracker.java` — in-memory derivation graph: derived fact → (rule, input facts) → upstream derived facts → base facts/events
- [ ] `DerivationTree.java` — tree/DAG structure for a single derived fact's full provenance
- [ ] `Explainer.java` — walk derivation tree → produce human-readable text (template-based)
- [ ] `ProvenanceStore.java` — async batched persistence to `fact_provenance` + `fact_modifications` tables; bounded queue (10K), batch flush (500)
- [ ] `ProvenanceExporter.java` — export derivation graph as DOT or JSON
- [ ] `ProvenanceResource.java` — all provenance endpoints: `/provenance/{factId}`, `/tree`, `/explain`, `/graph`, `/impact`, `/by-rule/{name}`
- [ ] DTOs: `ProvenanceResponse`, `ExplainResponse`
- [ ] Wire listeners into `EngineSession` (and re-wire during hot swap)
- [ ] `ProvenanceTrackerTest.java` — rule fires → derivation tree correctly links inputs to output
- [ ] `ExplainerTest.java` — derivation tree → readable explanation text
- [ ] `CepProvenanceTest.java` — event-triggered rule → provenance includes temporal context (window, event timestamps)

**Test:** Insert facts → rules fire → `GET /provenance/{factId}/explain` returns accurate multi-step explanation.

---

### Phase 5: Rule Hot Swap
**Goal:** Upload new DRL, zero-downtime transition to new rules.

- [ ] `RuleHotSwapper.java` — the 15-step procedure: compile → validate compatibility → lock → checkpoint → new session → transfer facts → replay events → wire listeners → fire → swap → dispose old → persist version → unlock
- [ ] Rule version persistence: `rule_versions` table (jOOQ)
- [ ] `RuleResource.java` — `POST /api/v1/rules` (upload + activate), `POST /rules/validate`, `GET /rules/current`, `GET /rules/versions`, `POST /rules/rollback/{versionId}`
- [ ] DTOs: `RuleUploadRequest`, `RuleUploadResponse`
- [ ] Rollback: restore from pre-swap checkpoint if any step fails
- [ ] `RuleHotSwapperTest.java` — swap rules → base facts preserved → derived facts re-derived under new rules
- [ ] `HotSwapWithCepTest.java` — swap while events are in-flight → events replayed into new session → temporal rules still work

**Test:** Upload new DRL → old derived facts retracted → new derived facts appear → base facts untouched → no downtime.

---

### Phase 6: Auth + API Hardening
**Goal:** Secure the API, add query support, SSE streaming.

- [ ] `ApiKeyFilter.java` — `@Provider` JAX-RS filter; hash incoming key, look up in `api_keys`, check permissions against endpoint
- [ ] `QueryResource.java` — `POST /api/v1/queries/{queryName}` executes DRL named queries; `GET /queries` lists available queries
- [ ] `EventStreamResource.java` — SSE endpoints: `/stream`, `/stream/derived`, `/stream/derived/{type}`; emit events on fact changes
- [ ] `HealthResource.java` — readiness (session loaded + DB reachable) and liveness (thread alive) probes
- [ ] DTOs: `QueryRequest`, `QueryResponse`, `HealthResponse`
- [ ] Derived facts API: `GET /api/v1/derived`, `GET /api/v1/derived/{factId}`
- [ ] `AuthFilterTest.java` — missing key → 401, wrong permissions → 403, valid key → pass
- [ ] OpenAPI annotations on all resources; verify Swagger UI at `/q/swagger-ui`

**Test:** Full API surface exercised with correct and incorrect API keys.

---

### Phase 7: Observability + Deployment
**Goal:** Production-ready metrics, Docker image, K8s manifests.

- [ ] Micrometer metrics: all counters/gauges/histograms listed in Observability section
- [ ] Graceful shutdown: `@PreDestroy` → drain → final checkpoint → flush provenance → halt session → close DB
- [ ] Dockerfile: multi-stage build (JDK build → JRE runtime)
- [ ] K8s manifests: Deployment (Recreate), Service, ConfigMap, HPA
- [ ] `FullLifecycleTest.java` — end-to-end: bootstrap → changesets → checkpoint → hot swap → events → provenance → recovery

**Test:** `docker build` succeeds; container starts and passes readiness probe; Prometheus scrape returns metrics.

---

### Phase Dependency Graph

```
Phase 0 ─→ Phase 1 ─→ Phase 2 ─→ Phase 3
                │                     │
                └──→ Phase 4 ←────────┘
                         │
                         ▼
                     Phase 5 ─→ Phase 6 ─→ Phase 7
```

Phases 2 and 4 can partially overlap (provenance doesn't need checkpoints, but CEP provenance tests need Phase 3).
Phase 5 depends on 2 (checkpoints for rollback), 3 (event replay), and 4 (re-wire listeners).
Phase 6 and 7 are hardening — no new engine logic.
