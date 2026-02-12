# Decision Engine — Technical Architecture (v2)

## Overview

A long-lived, stateful decision engine built on Drools with:
- Complex Event Processing (temporal reasoning, sliding windows)
- Replayable changesets as the sole write path
- PostgreSQL-backed checkpoints with deterministic replay
- Full-depth fact provenance with human-readable explanations
- REST API for all consumers (services, dashboards, third parties, CLI)
- Zero-downtime rule hot swap via DRL upload

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

---

## Technology Stack

| Component | Technology | Rationale |
|-----------|-----------|-----------|
| **Runtime** | Java 21 (virtual threads) | Best Drools compatibility; virtual threads for cheap REST concurrency |
| **Framework** | Quarkus 3.x | Fastest startup, lowest footprint, native Drools/KIE ecosystem affinity, dev mode with live reload |
| **Build** | Gradle (Kotlin DSL) | Fast incremental builds, `--continuous` for test-driven DRL development |
| **Rule engine** | Drools 8.x (latest) | PHREAK algorithm, TMS, CEP/Fusion, temporal operators |
| **Database** | PostgreSQL 16 | JSONB for flexible storage, BYTEA for checkpoint blobs, robust transactions |
| **DB access** | jOOQ 3.19 | Type-safe SQL, no ORM magic, explicit queries, lightweight |
| **Migrations** | Flyway | Standard, Quarkus-integrated |
| **REST** | Quarkus RESTEasy Reactive | Non-blocking endpoints, SSE built in, OpenAPI generation |
| **Auth** | API keys (simple) → extensible to OAuth2/OIDC | Third-party access requires auth; internal services use shared keys initially |
| **Serialization** | Jackson (API), MessagePack (checkpoints) | JSON for humans, compact binary for storage |
| **Compression** | LZ4 | Fast compression for checkpoint blobs |
| **Testing** | JUnit 5 + Quarkus Test + Testcontainers | Real PostgreSQL in tests, no mocks |
| **API docs** | SmallRye OpenAPI (Swagger UI) | Auto-generated spec; clients can codegen |

---

## Key Directories

- `src/main/java/com/sky/synome/` — Main application code
  - `config/` — CDI producers and typed config (`JooqProducer`, `EngineConfig`)
  - `core/` — Engine session lifecycle, changeset processing, rule compilation, hot swap
  - `changeset/` — Changeset model, log, replayer, validator
  - `checkpoint/` — Serialization (MessagePack + LZ4), PostgreSQL persistence, recovery
  - `provenance/` — Derivation tracking, explanation, async persistence
  - `api/` — REST resources and DTOs
- `src/main/resources/db/migration/` — Flyway SQL migrations (V001–V005)
- `src/main/resources/rules/` — DRL rule files
- `src/main/resources/application.properties` — Quarkus config (dev/prod profiles)
- `gradle/libs.versions.toml` — Version catalog for all dependencies

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
    engine_clock_at BIGINT NOT NULL,
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
  GET    /api/v1/health                         Readiness
  GET    /api/v1/health/live                    Liveness

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
  HEALTH_READ        GET /health

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
