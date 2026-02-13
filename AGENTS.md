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

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for full technical architecture and [docs/PLANS.md](docs/PLANS.md) for implementation phases.

## Configuration

- `docker-compose.yml` — PostgreSQL 16 for local dev (user: `engine`, password: `engine_dev`, db: `decision_engine`, port: 5432)
- `application.properties` — Quarkus profiles: `%dev` for local, `%prod` for production
- `gradle/libs.versions.toml` — Dependency versions (Quarkus 3.23.0, Drools 8.44.2, jOOQ 3.19.18)
- `gradle.properties` — JVM args for Gradle daemon
