package com.sky.synome.checkpoint;

import com.sky.synome.config.EngineConfig;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CheckpointScheduler {

  private static final Logger LOG = Logger.getLogger(CheckpointScheduler.class);

  @Inject EngineConfig engineConfig;

  @Inject CheckpointService checkpointService;

  private volatile Instant lastSuccessAt;
  private volatile Instant lastFailureAt;
  private volatile String lastFailureMessage;

  @Scheduled(
      every = "{engine.checkpoint-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
      identity = "engine-checkpoint-scheduler")
  void runPeriodicCheckpoint() {
    if (!engineConfig.checkpointEnabled() || !engineConfig.checkpointSchedulerEnabled()) {
      return;
    }
    try {
      checkpointService.createCheckpoint("scheduled");
      lastSuccessAt = Instant.now();
      lastFailureAt = null;
      lastFailureMessage = null;
    } catch (RuntimeException e) {
      lastFailureAt = Instant.now();
      lastFailureMessage = e.getMessage();
      LOG.warn("Scheduled checkpoint failed", e);
    }
  }

  public Instant lastSuccessAt() {
    return lastSuccessAt;
  }

  public Instant lastFailureAt() {
    return lastFailureAt;
  }

  public String lastFailureMessage() {
    return lastFailureMessage;
  }
}
