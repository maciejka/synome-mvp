package com.sky.synome.security;

import com.sky.synome.config.EngineConfig;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ApiKeyBootstrap {

  private static final Logger LOG = Logger.getLogger(ApiKeyBootstrap.class);

  @Inject EngineConfig engineConfig;

  @Inject ApiKeyService apiKeyService;

  void onStartup(@Observes StartupEvent startupEvent) {
    if (!engineConfig.securityEnabled()) {
      return;
    }

    String bootstrapKey = engineConfig.securityBootstrapKey();
    if (bootstrapKey == null || bootstrapKey.isBlank()) {
      return;
    }

    apiKeyService.ensureBootstrapKey(
        bootstrapKey,
        engineConfig.securityBootstrapName(),
        ApiPermission.parseCsv(engineConfig.securityBootstrapPermissions()));

    LOG.infof("Security bootstrap API key ensured for '%s'", engineConfig.securityBootstrapName());
  }
}
