package com.sky.synome.ops;

import com.sky.synome.checkpoint.CheckpointService;
import com.sky.synome.config.EngineConfig;
import com.sky.synome.provenance.ProvenancePersistenceService;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ShutdownCoordinator {

  private static final Logger LOG = Logger.getLogger(ShutdownCoordinator.class);

  @Inject EngineConfig engineConfig;

  @Inject EngineLifecycle engineLifecycle;

  @Inject CheckpointService checkpointService;

  @Inject ProvenancePersistenceService provenancePersistenceService;

  void onShutdown(@Observes ShutdownEvent shutdownEvent) {
    engineLifecycle.markShutdown("shutdown_start", "Controlled shutdown started");

    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future<?> finalizationTask = executor.submit(this::runFinalization);
    try {
      finalizationTask.get(engineConfig.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException timeoutException) {
      finalizationTask.cancel(true);
      LOG.warnf(
          "Shutdown finalization exceeded %dms timeout; continuing shutdown",
          engineConfig.shutdownTimeout().toMillis());
    } catch (InterruptedException interruptedException) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException executionException) {
      LOG.warn("Shutdown finalization failed", executionException.getCause());
    } finally {
      executor.shutdownNow();
      engineLifecycle.markShutdown("shutdown_complete", "Controlled shutdown completed");
    }
  }

  private void runFinalization() {
    try {
      checkpointService.createCheckpoint("shutdown-final");
    } catch (RuntimeException checkpointFailure) {
      LOG.warn("Final checkpoint during shutdown failed", checkpointFailure);
    }

    try {
      provenancePersistenceService.flushNow();
    } catch (RuntimeException flushFailure) {
      LOG.warn("Provenance flush during shutdown failed", flushFailure);
    }
  }
}
