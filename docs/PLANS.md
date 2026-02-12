# Implementation Phases

### Phase 0: Project Skeleton ✅ (`5a7092a`)
**Goal:** Buildable, runnable, empty Quarkus app with all tooling wired.

- [x] Initialize Gradle project with Kotlin DSL, `libs.versions.toml`
- [x] Add Quarkus BOM, Drools 8.x, jOOQ, Flyway, Jackson, LZ4, MessagePack dependencies
- [x] `DecisionEngineApp.java` — bare Quarkus main class
- [x] `application.properties` — dev profile with PostgreSQL, Flyway, logging
- [x] `docker-compose.yml` — PostgreSQL 16 for local dev
- [x] Flyway migrations: `V001` through `V005` (all five tables)
- [x] `JooqProducer.java` — CDI producer for `DSLContext`
- [x] Verify: `gradle quarkusDev` starts, Flyway runs, `/q/health` responds

**Test:** App starts, schema exists, jOOQ can query `changeset_log`.

**Implemented files:**
`build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`,
`docker-compose.yml`, `.gitignore`, `CLAUDE.md`,
`src/main/java/com/sky/synome/DecisionEngineApp.java`,
`src/main/java/com/sky/synome/config/JooqProducer.java`,
`src/main/resources/application.properties`,
`src/main/resources/db/migration/V001–V005`

---

### Phase 1: Engine Core — Session + Changeset Processing ✅ (`7ee468c`)
**Goal:** Accept a changeset via REST, insert base facts into a live KieSession, fire rules, return results.

- [x] `EngineConfig.java` — typed config (`@ConfigMapping`) for engine settings
- [x] `RuleCompiler.java` — compile DRL string → `KieBase` (STREAM mode, EQUALITY, pseudo clock)
- [x] `EngineSession.java` — lifecycle: create KieSession, wire clock, `fireUntilHalt()` on background thread, `halt()`/`dispose()`
- [x] `SessionLock.java` — `ReentrantLock`-based single-writer guard
- [x] `FactRegistry.java` — `Map<String, FactHandle>` for base facts, keyed by `factKey`
- [x] `Changeset.java`, `ChangesetEntry.java`, `EntryKind.java`, `ChangesetAction.java` — immutable records
- [x] `ChangesetValidator.java` — validate structure, fact types against KieBase
- [x] `ChangesetProcessor.java` — acquire lock → validate → apply UPSERT/DELETE → `fireAllRules()` → return effects
- [x] `ChangesetLog.java` — append to `changeset_log` table via jOOQ
- [x] `ChangesetResource.java` — `POST /api/v1/changesets`, `GET /api/v1/changesets`, `GET /api/v1/changesets/{id}`
- [x] `FactResource.java` — `GET /api/v1/facts`, `GET /api/v1/facts/{factKey}`, `/types`, `/stats`
- [x] DTOs: `ChangesetRequest`, `ChangesetResponse`, `FactResponse`, `ErrorResponse` (+ `DerivedFactSummary`, `EffectsSummary`)
- [x] `ErrorMapper.java` — structured error responses
- [x] Bootstrap DRL: `bootstrap-rules.drl` with a simple test rule using `insertLogical()`
- [x] `EngineSessionTest.java` — session starts, accepts facts, fires rules
- [x] `ChangesetProcessorTest.java` — UPSERT inserts, re-UPSERT updates, DELETE removes, effects counted

**Extra:** `DerivationTracker.java` — lightweight derivation tracking via `RuleRuntimeEventListener` (early provenance groundwork, not full Phase 4 scope).

**Implemented files:**
`src/main/java/com/sky/synome/config/EngineConfig.java`,
`src/main/java/com/sky/synome/core/{EngineSession,RuleCompiler,FactRegistry,SessionLock,DerivationTracker}.java`,
`src/main/java/com/sky/synome/changeset/{Changeset,ChangesetEntry,EntryKind,ChangesetAction,ChangesetLog,ChangesetProcessor,ChangesetValidator}.java`,
`src/main/java/com/sky/synome/api/{ChangesetResource,FactResource,ErrorMapper}.java`,
`src/main/java/com/sky/synome/api/dto/{ChangesetRequest,ChangesetResponse,DerivedFactSummary,EffectsSummary,ErrorResponse,FactResponse}.java`,
`src/main/resources/rules/bootstrap-rules.drl`,
`src/test/java/com/sky/synome/changeset/ChangesetProcessorTest.java`,
`src/test/java/com/sky/synome/core/EngineSessionTest.java`

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

**Note:** Phase 1 included an early `DerivationTracker.java` that captures basic rule-firing context, but the full provenance system (graph, persistence, explanation, REST API) is not yet implemented.

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

### Phase 6: Auth, API Hardening + Graceful Shutdown
**Goal:** Secure the API, add query support, SSE streaming, graceful shutdown.

- [ ] `ApiKeyFilter.java` — `@Provider` JAX-RS filter; hash incoming key, look up in `api_keys`, check permissions against endpoint
- [ ] `QueryResource.java` — `POST /api/v1/queries/{queryName}` executes DRL named queries; `GET /queries` lists available queries
- [ ] `EventStreamResource.java` — SSE endpoints: `/stream`, `/stream/derived`, `/stream/derived/{type}`; emit events on fact changes
- [ ] `HealthResource.java` — readiness (session loaded + DB reachable) and liveness (thread alive) probes
- [ ] DTOs: `QueryRequest`, `QueryResponse`, `HealthResponse`
- [ ] Derived facts API: `GET /api/v1/derived`, `GET /api/v1/derived/{factId}`
- [ ] `AuthFilterTest.java` — missing key → 401, wrong permissions → 403, valid key → pass
- [ ] OpenAPI annotations on all resources; verify Swagger UI at `/q/swagger-ui`
- [ ] Graceful shutdown: `@PreDestroy` → drain → final checkpoint → flush provenance → halt session → close DB
- [ ] `FullLifecycleTest.java` — end-to-end: bootstrap → changesets → checkpoint → hot swap → events → provenance → recovery

**Test:** Full API surface exercised with correct and incorrect API keys. Graceful shutdown produces final checkpoint.

---

### Phase Dependency Graph

```
Phase 0 ─→ Phase 1 ─→ Phase 2 ─→ Phase 3
                │                     │
                └──→ Phase 4 ←────────┘
                         │
                         ▼
                     Phase 5 ─→ Phase 6
```

Phases 2 and 4 can partially overlap (provenance doesn't need checkpoints, but CEP provenance tests need Phase 3).
Phase 5 depends on 2 (checkpoints for rollback), 3 (event replay), and 4 (re-wire listeners).
Phase 6 is hardening — no new engine logic.
