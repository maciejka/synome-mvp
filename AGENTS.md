# AGENTS.md

This file provides guidance to software agents when working with code in this repository.

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

The project enforces six QA tools from `build.gradle.kts`:

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

- **PMD + CPD** (complexity and duplication checks)
  - Purpose: Enforces complexity thresholds via PMD and copy/paste duplication detection via PMD CPD.
  - Config: `config/pmd/ruleset.xml` and Gradle CPD task settings in `build.gradle.kts`.
  - Scope: `pmdMain`, `pmdTest`, `cpdMain`, and `cpdTest`.
  - Commands:
    - Run PMD checks: `./gradlew pmdMain pmdTest`
    - Run CPD checks: `./gradlew cpdMain cpdTest`

- **OWASP Dependency-Check** (dependency security scan)
  - Purpose: Detects known vulnerabilities in project dependencies.
  - Config: `build.gradle.kts` (`dependencyCheck { ... }`)
  - Note: Set `NVD_API_KEY` in your environment for faster NVD feed updates.
  - Commands:
    - Run dependency vulnerability analysis: `./gradlew dependencyCheckAnalyze`

- **JaCoCo** (test coverage reporting)
  - Purpose: Generates test coverage reports after tests.
  - Config: `build.gradle.kts` (`jacoco { toolVersion = "0.8.12" }`)
  - Reports: XML and HTML enabled (`jacocoTestReport`), and `test` finalizes with this report task.
  - Commands:
    - Run tests with coverage report generation: `./gradlew test`
    - Generate report explicitly: `./gradlew jacocoTestReport`

Recommended local QA pass:

```bash
./gradlew spotlessCheck checkstyleMain checkstyleTest spotbugsMain spotbugsTest pmdMain pmdTest cpdMain cpdTest test
```

## Commit gate policy:

- Run the aggregate QA task manually before every commit:
  - `./gradlew qa`
- Do not create commits when `./gradlew qa` fails.
- Enforce commit quality thresholds before every commit:
  - Static analysis: zero new findings (no new SpotBugs/Checkstyle issues, no warning regressions).
  - Complexity limits: no new methods over agreed thresholds (for example cyclomatic `> 10` or cognitive `> 15`).
  - Duplication: no new duplicate blocks; duplication ratio must stay stable or lower.
- Keep commit subjects aligned with existing repository history:
  - Use a capitalized imperative summary without a `type:` prefix (for example: `Document commit message style policy`)
  - Keep subject short, descriptive, and without trailing punctuation.
- For every commit created by Codex, add this trailer to the commit message:
  - `Co-Authored-By: Codex <noreply@openai.com>`

## Architecture

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for full technical architecture and [docs/PLANS.md](docs/PLANS.md) for implementation phases.

## Configuration

- `docker-compose.yml` — PostgreSQL 16 for local dev (user: `engine`, password: `engine_dev`, db: `decision_engine`, port: 5432)
- `application.properties` — Quarkus profiles: `%dev` for local, `%prod` for production
- `gradle/libs.versions.toml` — Dependency versions (Quarkus 3.23.0, Drools 8.44.2, jOOQ 3.19.18)
- `gradle.properties` — JVM args for Gradle daemon
