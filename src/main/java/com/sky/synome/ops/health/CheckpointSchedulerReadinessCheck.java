package com.sky.synome.ops.health;

import com.sky.synome.checkpoint.CheckpointScheduler;
import com.sky.synome.config.EngineConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
public class CheckpointSchedulerReadinessCheck implements HealthCheck {

  @Inject EngineConfig engineConfig;

  @Inject CheckpointScheduler checkpointScheduler;

  @Override
  public HealthCheckResponse call() {
    if (!engineConfig.checkpointEnabled() || !engineConfig.checkpointSchedulerEnabled()) {
      return HealthCheckResponse.named("checkpoint-scheduler")
          .up()
          .withData("enabled", false)
          .build();
    }

    Instant lastFailureAt = checkpointScheduler.lastFailureAt();
    Instant lastSuccessAt = checkpointScheduler.lastSuccessAt();
    if (lastFailureAt != null && (lastSuccessAt == null || lastFailureAt.isAfter(lastSuccessAt))) {
      return HealthCheckResponse.named("checkpoint-scheduler")
          .down()
          .withData("enabled", true)
          .withData("lastFailureAt", lastFailureAt.toString())
          .withData("lastFailure", checkpointScheduler.lastFailureMessage())
          .build();
    }

    var builder = HealthCheckResponse.named("checkpoint-scheduler").up().withData("enabled", true);
    if (lastSuccessAt != null) {
      builder.withData("lastSuccessAt", lastSuccessAt.toString());
    }
    return builder.build();
  }
}
