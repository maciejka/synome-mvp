# M6 Execution Plan: API Hardening and Operations Lifecycle

## 1. Objective

Secure and operationalize the full API surface with API-key authorization, operational streams, and graceful lifecycle behavior.

## 2. Current Baseline (2026-02-13)

Implemented:

- Core write/read endpoints for changesets and checkpoints.
- Fact query endpoints.
- Global error mapper and contract tests.
- Base health support via SmallRye Health.
- `api_keys` schema exists.

Missing:

- API key authentication filter and permission checks.
- Operational SSE feeds.
- Derived fact/event APIs aligned with architecture target.
- Graceful shutdown flow with final checkpoint and flush.
- Full lifecycle security and operations test suite.

## 3. Scope

In scope:

- API key authn/authz using `api_keys` table.
- Permission matrix and endpoint-level enforcement.
- SSE endpoints for runtime events/operations.
- Lifecycle controls (readiness gating, graceful shutdown, final checkpoint).
- End-to-end operational test coverage.

Out of scope:

- External IAM integration.
- Multi-region failover orchestration.

## 4. Definition of Done

1. All business endpoints require valid `X-Api-Key` with required permissions.
2. Health/readiness reflect engine state accurately during startup/recovery/shutdown.
3. SSE resources stream key runtime and operational events.
4. Graceful shutdown performs final checkpoint + async flush and blocks new writes.
5. Lifecycle and security tests prove operational behavior under restart/failure scenarios.

## 5. Core Design Decisions

1. Key storage:
- Store hashes only (no plaintext keys).
- Support key rotation with overlap window.

2. Authorization model:
- Permission constants mapped to endpoint groups (`CHANGESET_WRITE`, `FACT_READ`, `CHECKPOINT_ADMIN`, `RULE_ADMIN`, `PROVENANCE_READ`, `OPS_STREAM_READ`).
- Use a request filter + annotation-based permission checks.

3. Readiness gating:
- Not ready during startup recovery and during controlled shutdown.
- Ready only when engine session is accepting writes.

4. Graceful shutdown policy:
- Stop accepting writes.
- Trigger final checkpoint.
- Flush async workers (provenance/events).
- Dispose session.

## 6. Work Breakdown Structure

### Phase 0: Auth Contract and Key Management

Deliverables:

- API key data model and permission contract.

Implementation tasks:

- Add key hashing utility and key management service.
- Add admin bootstrap mechanism for initial key creation (dev/test profile friendly).
- Define permission matrix documentation for endpoints.

Validation:

- Unit tests for hash/verify and permission parsing.

### Phase 1: Authentication and Authorization Filters

Deliverables:

- Request filter enforcing API key validation and permission checks.

Implementation tasks:

- Implement `ApiKeyAuthFilter` for `X-Api-Key`.
- Implement permission annotation/interceptor (`@RequiresPermission`).
- Apply permission annotations to existing resources.

Validation:

- API contract tests:
- missing key -> `401`
- inactive/expired key -> `401`
- insufficient permissions -> `403`
- valid key -> existing success behavior unchanged

### Phase 2: Operational and Query Surface Expansion

Deliverables:

- SSE + additional operational query resources.

Implementation tasks:

- Add SSE endpoints:
- `GET /api/v1/ops/stream/changesets`
- `GET /api/v1/ops/stream/checkpoints`
- `GET /api/v1/ops/stream/recovery`
- Add derived/event query endpoints aligned with M3/M4 outputs.

Validation:

- Contract tests for SSE handshake/auth and stream event structure.

### Phase 3: Health and Readiness Hardening

Deliverables:

- Explicit readiness/liveness checks tied to engine lifecycle.

Implementation tasks:

- Add health checks:
- datasource migration readiness
- engine session readiness
- recovery status
- checkpoint scheduler health
- Ensure readiness toggles correctly during startup and shutdown phases.

Validation:

- Integration tests for readiness transitions.

### Phase 4: Graceful Shutdown Lifecycle

Deliverables:

- Deterministic shutdown sequence.

Implementation tasks:

- Add shutdown coordinator:
- set maintenance/read-only mode
- acquire session lock
- create final checkpoint
- flush async services
- release resources
- Add timeout and fallback policy when final checkpoint fails.

Validation:

- Integration tests for shutdown under idle and active write load.

### Phase 5: Full Lifecycle QA Gate

Deliverables:

- Security and operations confidence gate.

Implementation tasks:

- Add scenario suite:
- cold start with recovery
- auth-protected traffic
- checkpoint/recovery operations
- controlled shutdown/restart
- Run `./gradlew qa`.

Validation:

- Full lifecycle suite stable in CI.

## 7. Concrete File Plan

Likely new files:

- `src/main/java/com/sky/synome/security/ApiKeyService.java`
- `src/main/java/com/sky/synome/security/ApiKeyAuthFilter.java`
- `src/main/java/com/sky/synome/security/RequiresPermission.java`
- `src/main/java/com/sky/synome/security/PermissionInterceptor.java`
- `src/main/java/com/sky/synome/ops/ShutdownCoordinator.java`
- `src/main/java/com/sky/synome/ops/stream/OperationsStreamResource.java`
- `src/test/java/com/sky/synome/security/*.java`
- `src/test/java/com/sky/synome/api/SecurityApiContractTest.java`
- `src/test/java/com/sky/synome/ops/LifecycleIntegrationTest.java`

Likely updated files:

- `src/main/java/com/sky/synome/api/ChangesetResource.java`
- `src/main/java/com/sky/synome/api/CheckpointResource.java`
- `src/main/java/com/sky/synome/api/FactResource.java`
- `src/main/resources/application.properties`
- `docs/PLANS.md`

## 8. Recommended Delivery Slices

1. PR1: API key service + auth filter + permission annotations.
2. PR2: Security contract tests across existing endpoints.
3. PR3: SSE and operations query endpoints.
4. PR4: Readiness/liveness hardening + graceful shutdown coordinator.
5. PR5: Full lifecycle integration suite + docs status update.

## 9. Risks and Mitigations

1. Risk: auth rollout breaks existing contract tests.
- Mitigation: update test fixtures with generated dev keys and explicit permission setup.

2. Risk: graceful shutdown blocks too long under load.
- Mitigation: bounded shutdown timeout with staged fallback and clear logs.

3. Risk: SSE stream fan-out impacts memory usage.
- Mitigation: bounded subscriber limits and backpressure/disconnect policy.

## 10. Exit Checklist

- [x] API key authn/authz enforced across business endpoints.
- [x] Permission matrix documented and tested.
- [x] SSE resources merged with auth controls.
- [x] Graceful shutdown with final checkpoint merged.
- [x] Lifecycle/security integration tests and `./gradlew qa` green.
- [x] `docs/PLANS.md` M6 moved to `DONE`.
