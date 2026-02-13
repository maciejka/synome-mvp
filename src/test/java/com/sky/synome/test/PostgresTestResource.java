package com.sky.synome.test;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;
import org.jboss.logging.Logger;
import org.testcontainers.containers.PostgreSQLContainer;

public class PostgresTestResource implements QuarkusTestResourceLifecycleManager {

  private static final Logger LOG = Logger.getLogger(PostgresTestResource.class);
  private static final String TESTCONTAINERS_ENABLED_KEY = "synome.testcontainers.enabled";

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("decision_engine")
          .withUsername("engine")
          .withPassword("engine_dev");

  @Override
  public Map<String, String> start() {
    if (!isTestcontainersEnabled()) {
      return localhostDatasource();
    }

    try {
      if (!POSTGRES.isRunning()) {
        POSTGRES.start();
      }
      return Map.of(
          "quarkus.datasource.db-kind",
          "postgresql",
          "quarkus.datasource.jdbc.url",
          POSTGRES.getJdbcUrl(),
          "quarkus.datasource.username",
          POSTGRES.getUsername(),
          "quarkus.datasource.password",
          POSTGRES.getPassword(),
          "quarkus.datasource.devservices.enabled",
          "false",
          "quarkus.flyway.migrate-at-start",
          "true");
    } catch (RuntimeException e) {
      LOG.warn("Testcontainers PostgreSQL unavailable, falling back to localhost test datasource");
      LOG.debug("Testcontainers startup failure details", e);
      return localhostDatasource();
    }
  }

  @Override
  public void stop() {
    // Keep container running for the full test run for faster execution.
  }

  private static boolean isTestcontainersEnabled() {
    String configured = System.getProperty(TESTCONTAINERS_ENABLED_KEY);
    if (configured == null || configured.isBlank()) {
      configured = System.getenv("SYNOME_TESTCONTAINERS_ENABLED");
    }
    if (configured == null || configured.isBlank()) {
      return true;
    }
    return Boolean.parseBoolean(configured);
  }

  private static Map<String, String> localhostDatasource() {
    return Map.of(
        "quarkus.datasource.db-kind",
        "postgresql",
        "quarkus.datasource.jdbc.url",
        "jdbc:postgresql://localhost:5432/decision_engine",
        "quarkus.datasource.username",
        "engine",
        "quarkus.datasource.password",
        "engine_dev",
        "quarkus.datasource.devservices.enabled",
        "false",
        "quarkus.flyway.migrate-at-start",
        "true");
  }
}
