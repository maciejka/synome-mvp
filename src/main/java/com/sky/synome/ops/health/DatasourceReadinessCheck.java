package com.sky.synome.ops.health;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.jooq.DSLContext;

@Readiness
@ApplicationScoped
public class DatasourceReadinessCheck implements HealthCheck {

  @Inject DSLContext dsl;

  @Override
  public HealthCheckResponse call() {
    try {
      dsl.fetchValue("select 1");
      return HealthCheckResponse.named("datasource-ready").up().build();
    } catch (RuntimeException runtimeException) {
      return HealthCheckResponse.named("datasource-ready")
          .down()
          .withData("error", runtimeException.getClass().getSimpleName())
          .build();
    }
  }
}
