package com.sky.synome.checkpoint;

import com.sky.synome.config.EngineConfig;
import com.sky.synome.core.EngineSession;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

@ApplicationScoped
public class RecoveryStartup {

  @Inject EngineConfig engineConfig;

  @Inject RecoveryOrchestrator recoveryOrchestrator;

  @Inject EngineSession engineSession;

  void onStartup(@Observes StartupEvent event) {
    if (!engineConfig.recoveryEnabled()) {
      return;
    }
    recoveryOrchestrator.recover(engineSession);
  }
}
