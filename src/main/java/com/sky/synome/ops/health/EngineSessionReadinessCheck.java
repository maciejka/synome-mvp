package com.sky.synome.ops.health;

import com.sky.synome.core.EngineSession;
import com.sky.synome.ops.EngineLifecycle;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
public class EngineSessionReadinessCheck implements HealthCheck {

  @Inject EngineLifecycle engineLifecycle;

  @Inject EngineSession engineSession;

  @Override
  public HealthCheckResponse call() {
    boolean sessionReady = engineSession.kieSession() != null;
    if (engineLifecycle.isReady() && sessionReady) {
      return HealthCheckResponse.named("engine-session-ready")
          .up()
          .withData("state", engineLifecycle.currentState().name())
          .build();
    }

    return HealthCheckResponse.named("engine-session-ready")
        .down()
        .withData("state", engineLifecycle.currentState().name())
        .withData("sessionReady", sessionReady)
        .build();
  }
}
