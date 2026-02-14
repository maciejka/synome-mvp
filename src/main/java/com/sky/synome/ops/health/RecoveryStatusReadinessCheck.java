package com.sky.synome.ops.health;

import com.sky.synome.ops.EngineLifecycle;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
public class RecoveryStatusReadinessCheck implements HealthCheck {

  @Inject EngineLifecycle engineLifecycle;

  @Override
  public HealthCheckResponse call() {
    boolean recovering = engineLifecycle.currentState() == EngineLifecycle.State.RECOVERING;
    if (recovering) {
      return HealthCheckResponse.named("engine-recovery-status")
          .down()
          .withData("state", engineLifecycle.currentState().name())
          .build();
    }

    return HealthCheckResponse.named("engine-recovery-status")
        .up()
        .withData("state", engineLifecycle.currentState().name())
        .build();
  }
}
