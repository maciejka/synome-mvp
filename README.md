# Synome MVP (Decision Engine Prototype)

Synome MVP is a stateful rule engine prototype built on Drools 8.x + Quarkus 3.x.

## What

- A long-lived `KieSession` with single-writer changeset processing.
- Deterministic recovery from PostgreSQL checkpoints + replay.
- CEP event replay window support (`EVENT/EMIT` through configured entry points).
- Provenance capture and explainability APIs for derived facts.
- Rule version upload/validate/activate/rollback with safe runtime swap.
- API key authentication + permission-based authorization for `/api/v1/**`.

## Why

The prototype validates core guarantees before product hardening:

- deterministic behavior across restart and replay,
- strict idempotent write semantics,
- explainable decisions,
- zero-downtime rule evolution.

## How

### Local startup

```bash
docker compose up -d
./gradlew quarkusDev
```

- Swagger UI: `http://localhost:8080/q/swagger-ui`
- Health: `http://localhost:8080/q/health`
- Readiness: `http://localhost:8080/q/health/ready`

### Authentication in dev

All business endpoints require `X-Api-Key`.

- Dev bootstrap key default: `dev-local-api-key`
- Header example: `X-Api-Key: dev-local-api-key`

### Tests

```bash
./gradlew test
./gradlew qa
```

## Documentation Map

- `docs/ARCHITECTURE.md`: current prototype architecture and invariants.
- `docs/API.md`: API surface, request/response contracts, and common flows.
- `docs/API_PERMISSIONS.md`: permission matrix for each endpoint.
- `docs/CONFIGURATION.md`: `engine.*` runtime settings and operational impact.
- `docs/RUNBOOK.md`: startup/recovery/security/stream/shutdown operations guide.
- `docs/PLANS.md`: current status and near-term documentation backlog.

## Historical Plans

Detailed milestone execution plans are archived in `docs/exec-plans/completed/` and are not the source of truth for current runtime behavior.
