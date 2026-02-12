package com.sky.synome.core;

import com.sky.synome.config.EngineConfig;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.jboss.logging.Logger;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.KieSessionConfiguration;
import org.kie.api.runtime.conf.ClockTypeOption;

@Startup
@ApplicationScoped
public class EngineSession {

  private static final Logger LOG = Logger.getLogger(EngineSession.class);

  private final EngineConfig config;
  private final RuleCompiler compiler;

  private KieBase kieBase;
  private KieSession kieSession;
  private final FactRegistry factRegistry = new FactRegistry();
  private final SessionLock sessionLock = new SessionLock();
  private final DerivationTracker derivationTracker = new DerivationTracker();

  @Inject
  public EngineSession(EngineConfig config, RuleCompiler compiler) {
    this.config = config;
    this.compiler = compiler;
    initialize();
  }

  private void initialize() {
    String drlPath = config.rulesPath() + "/" + config.defaultRulesFile();
    LOG.infof("Loading DRL from classpath: %s", drlPath);

    String drl = loadDrl(drlPath);
    this.kieBase = compiler.compile(drl);

    KieSessionConfiguration sessionConfig = KieServices.Factory.get().newKieSessionConfiguration();
    sessionConfig.setOption(ClockTypeOption.PSEUDO);
    this.kieSession = kieBase.newKieSession(sessionConfig, null);
    this.kieSession.addEventListener(derivationTracker);

    LOG.infof(
        "Engine session initialized. Rules in KieBase: %d",
        kieBase.getKiePackages().stream().mapToInt(p -> p.getRules().size()).sum());
  }

  private String loadDrl(String path) {
    try (InputStream is =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
      if (is == null) {
        throw new IllegalStateException("DRL file not found on classpath: " + path);
      }
      return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to read DRL file: " + path, e);
    }
  }

  @PreDestroy
  void destroy() {
    if (kieSession != null) {
      LOG.info("Disposing KieSession");
      kieSession.dispose();
    }
  }

  public KieBase kieBase() {
    return kieBase;
  }

  public KieSession kieSession() {
    return kieSession;
  }

  public FactRegistry factRegistry() {
    return factRegistry;
  }

  public SessionLock sessionLock() {
    return sessionLock;
  }

  public DerivationTracker derivationTracker() {
    return derivationTracker;
  }
}
