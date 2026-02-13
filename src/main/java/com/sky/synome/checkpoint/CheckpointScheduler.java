package com.sky.synome.checkpoint;

import com.sky.synome.config.EngineConfig;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CheckpointScheduler {

  private static final Logger LOG = Logger.getLogger(CheckpointScheduler.class);

  @Inject EngineConfig engineConfig;

  @Inject CheckpointService checkpointService;

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
    } catch (RuntimeException e) {
      LOG.warn("Scheduled checkpoint failed", e);
    }
  }
}
