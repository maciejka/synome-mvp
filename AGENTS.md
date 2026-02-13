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

## Code Quality / QA Tools

The project enforces four QA tools from `build.gradle.kts`:

- **Spotless** (formatting)
  - Purpose: Enforces Google Java Format and basic whitespace/import hygiene.
  - Config: `build.gradle.kts` (`spotless { java { ... } }`)
  - Commands:
    - Check formatting: `./gradlew spotlessCheck`
    - Auto-fix formatting: `./gradlew spotlessApply`

- **Checkstyle** (style and static lint rules)
  - Purpose: Fails build on style violations and warnings (`maxWarnings = 0`, `isIgnoreFailures = false`).
  - Config: `config/checkstyle/checkstyle.xml`
  - Scope: Only `checkstyleMain` and `checkstyleTest` are enabled (hand-written code in `src/main/java` and `src/test/java`).
  - Commands:
    - Run all Checkstyle checks: `./gradlew checkstyleMain checkstyleTest`

- **SpotBugs** (bug pattern static analysis)
  - Purpose: Detects potential correctness bugs (confidence level `MEDIUM`, with repo-specific excludes).
  - Config: `config/spotbugs/exclude-filter.xml`
  - Scope: Only `spotbugsMain` and `spotbugsTest` are enabled.
  - Reports: HTML enabled, XML disabled.
  - Commands:
    - Run SpotBugs checks: `./gradlew spotbugsMain spotbugsTest`

- **JaCoCo** (test coverage reporting)
  - Purpose: Generates test coverage reports after tests.
  - Config: `build.gradle.kts` (`jacoco { toolVersion = "0.8.12" }`)
  - Reports: XML and HTML enabled (`jacocoTestReport`), and `test` finalizes with this report task.
  - Commands:
    - Run tests with coverage report generation: `./gradlew test`
    - Generate report explicitly: `./gradlew jacocoTestReport`

Recommended local QA pass:

```bash
./gradlew spotlessCheck checkstyleMain checkstyleTest spotbugsMain spotbugsTest test
```

Commit gate policy:

- Run the aggregate QA task manually before every commit:
  - `./gradlew qa`
- Do not create commits when `./gradlew qa` fails.
- For every commit created by Codex, add this trailer to the commit message:
  - `Co-Authored-By: Codex <noreply@openai.com>`

## Architecture

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for full technical architecture and [docs/PLANS.md](docs/PLANS.md) for implementation phases.

## Configuration

- `docker-compose.yml` — PostgreSQL 16 for local dev (user: `engine`, password: `engine_dev`, db: `decision_engine`, port: 5432)
- `application.properties` — Quarkus profiles: `%dev` for local, `%prod` for production
- `gradle/libs.versions.toml` — Dependency versions (Quarkus 3.23.0, Drools 8.44.2, jOOQ 3.19.18)
- `gradle.properties` — JVM args for Gradle daemon
