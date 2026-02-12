# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Synome MVP — a long-lived, stateful decision engine built on Drools 8.x with Quarkus 3.x. It provides Complex Event Processing (CEP), replayable changesets as the sole write path, PostgreSQL-backed checkpoints with deterministic replay, full-depth fact provenance, and a REST API. The project name in Gradle is `decision-engine`.

## Development Commands

### Setup
```bash
# Start PostgreSQL (required before running the app)
docker compose up -d

# Install dependencies (Gradle wrapper, no global install needed)
./gradlew dependencies
```

### Running the Application
```bash
# Dev mode with live reload + Swagger UI at /q/swagger-ui
./gradlew quarkusDev
```

### Testing
```bash
# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.sky.synome.core.ChangesetProcessorTest"

# Run tests in watch mode (re-runs on file change)
./gradlew test --continuous --tests "MyTest"
```

### Build
```bash
./gradlew build
```

## Architecture

### High-Level Structure

Java 21 + Quarkus 3.x REST backend with a Drools 8.x rule engine. Single-instance stateful service backed by PostgreSQL 16. Uses jOOQ for type-safe SQL (no ORM), Flyway for migrations, and Jackson for REST serialization.

### Key Directories

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

### Important Patterns

- **Changesets are the only write path.** No direct fact manipulation in working memory.
- **Three-way fact distinction:** base facts (persistent, checkpointed), events (temporal, auto-expiring via `@expires`, NOT checkpointed), derived facts (TMS-managed via `insertLogical()`, re-derived on restore).
- **Rules must use `insertLogical()` exclusively.** `insert()` in rule RHS is a bug.
- **Single writer:** one changeset at a time, enforced by `SessionLock`.
- **Checkpoint + replay = identical state.** Deterministic recovery via pseudo clock.
- **Provenance is non-blocking.** Async batched persistence to PostgreSQL.
- **Database migrations:** Flyway, auto-runs at startup. Tables: `changeset_log`, `checkpoints`, `fact_provenance`, `fact_modifications`, `rule_versions`, `api_keys`.
- **API auth:** API key via `X-Api-Key` header, permission-based access control.

## Configuration

- `docker-compose.yml` — PostgreSQL 16 for local dev (user: `engine`, password: `engine_dev`, db: `decision_engine`, port: 5432)
- `application.properties` — Quarkus profiles: `%dev` for local, `%prod` for production
- `gradle/libs.versions.toml` — Dependency versions (Quarkus 3.23.0, Drools 8.44.2, jOOQ 3.19.18)
- `gradle.properties` — JVM args for Gradle daemon
