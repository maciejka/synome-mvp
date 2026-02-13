package com.sky.synome.core;

import com.sky.synome.config.EngineConfig;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.definition.type.FactType;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.KieSessionConfiguration;
import org.kie.api.runtime.conf.ClockTypeOption;
import org.kie.api.runtime.rule.FactHandle;
import org.kie.api.time.SessionPseudoClock;

@Startup
@ApplicationScoped
public class EngineSession {

  private static final Logger LOG = Logger.getLogger(EngineSession.class);
  private static final String DRL_PACKAGE = "com.sky.synome.rules";

  private final EngineConfig config;
  private final RuleCompiler compiler;

  private KieBase kieBase;
  private KieSession kieSession;
  private final FactRegistry factRegistry = new FactRegistry();
  private final SessionLock sessionLock = new SessionLock();
  private final DerivationTracker derivationTracker = new DerivationTracker();

  public record RestorableFact(
      String factKey, String factType, Map<String, Object> data, Object factObject) {}

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
    this.kieSession = createSession();

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

  public void rebuildFromSnapshot(List<RestorableFact> snapshot) {
    restoreFromSnapshot(snapshot, 0L, true);
  }

  public void restoreFromSnapshot(
      List<RestorableFact> snapshot, long clockMillis, boolean fireRules) {
    if (kieSession != null) {
      kieSession.dispose();
    }
    this.kieSession = createSession();
    this.factRegistry.clear();

    advanceClockTo(clockMillis);

    for (RestorableFact fact : snapshot) {
      Object factObject =
          fact.factObject() != null ? fact.factObject() : createFact(fact.factType(), fact.data());
      FactHandle handle = this.kieSession.insert(factObject);
      Map<String, Object> dataSnapshot = fact.data() == null ? Map.of() : Map.copyOf(fact.data());
      this.factRegistry.put(
          fact.factKey(), new FactRegistry.FactEntry(handle, fact.factType(), dataSnapshot));
    }

    if (fireRules) {
      this.kieSession.fireAllRules();
    }
  }

  public List<RestorableFact> snapshotBaseFacts() {
    List<RestorableFact> snapshot = new ArrayList<>();
    for (Map.Entry<String, FactRegistry.FactEntry> entry : factRegistry.entries()) {
      FactRegistry.FactEntry factEntry = entry.getValue();
      Object factObject = kieSession.getObject(factEntry.handle());
      if (factObject == null) {
        continue;
      }
      Map<String, Object> dataSnapshot =
          factEntry.data() == null ? Map.of() : Map.copyOf(factEntry.data());
      snapshot.add(
          new RestorableFact(entry.getKey(), factEntry.factType(), dataSnapshot, factObject));
    }
    return snapshot;
  }

  public long currentClockMillis() {
    if (!(kieSession.getSessionClock() instanceof SessionPseudoClock clock)) {
      return 0L;
    }
    return clock.getCurrentTime();
  }

  public Object createFact(String factTypeName, Map<String, Object> data) {
    FactType factType = kieBase.getFactType(DRL_PACKAGE, factTypeName);
    if (factType == null) {
      throw new IllegalStateException("Unknown fact type: " + factTypeName);
    }

    try {
      Object instance = factType.newInstance();
      Map<String, Object> fields = data == null ? Map.of() : data;
      for (Map.Entry<String, Object> field : fields.entrySet()) {
        var fieldDef = factType.getField(field.getKey());
        if (fieldDef != null) {
          Object value = coerceValue(field.getValue(), fieldDef.getType());
          factType.set(instance, field.getKey(), value);
        }
      }
      return instance;
    } catch (InstantiationException | IllegalAccessException e) {
      throw new IllegalStateException("Failed to create fact of type " + factType.getName(), e);
    }
  }

  private KieSession createSession() {
    KieSessionConfiguration sessionConfig = KieServices.Factory.get().newKieSessionConfiguration();
    sessionConfig.setOption(ClockTypeOption.PSEUDO);
    KieSession session = kieBase.newKieSession(sessionConfig, null);
    session.addEventListener(derivationTracker);
    return session;
  }

  private void advanceClockTo(long clockMillis) {
    if (!(kieSession.getSessionClock() instanceof SessionPseudoClock clock)) {
      throw new IllegalStateException("KieSession is not configured with a pseudo clock");
    }
    long current = clock.getCurrentTime();
    if (clockMillis > current) {
      clock.advanceTime(clockMillis - current, TimeUnit.MILLISECONDS);
    }
  }

  private Object coerceValue(Object value, Class<?> targetType) {
    if (value == null) {
      return null;
    }
    if (targetType.isInstance(value)) {
      return value;
    }

    if (targetType == double.class || targetType == Double.class) {
      if (value instanceof Number n) {
        return n.doubleValue();
      }
    }
    if (targetType == long.class || targetType == Long.class) {
      if (value instanceof Number n) {
        return n.longValue();
      }
    }
    if (targetType == int.class || targetType == Integer.class) {
      if (value instanceof Number n) {
        return n.intValue();
      }
    }
    if (targetType == String.class) {
      return value.toString();
    }

    return value;
  }
}
