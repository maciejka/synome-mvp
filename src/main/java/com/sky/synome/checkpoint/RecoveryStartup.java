package com.sky.synome.checkpoint;

import com.sky.synome.config.EngineConfig;
import com.sky.synome.core.EngineSession;
import com.sky.synome.ops.EngineLifecycle;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

@ApplicationScoped
public class RecoveryStartup {

  @Inject EngineConfig engineConfig;

  @Inject RecoveryOrchestrator recoveryOrchestrator;

  @Inject EngineSession engineSession;

  @Inject EngineLifecycle engineLifecycle;

  void onStartup(@Observes StartupEvent event) {
    if (!engineConfig.recoveryEnabled()) {
      engineLifecycle.markRunning("startup", "Recovery disabled, engine running");
      return;
    }

    engineLifecycle.markRecovering("startup", "Recovery bootstrap started");
    try {
      recoveryOrchestrator.recover(engineSession);
      engineLifecycle.markRunning("recovery_complete", "Recovery bootstrap completed");
    } catch (RuntimeException recoveryFailure) {
      engineLifecycle.markRecovering("recovery_failure", recoveryFailure.getMessage());
      throw recoveryFailure;
    }
  }
}
