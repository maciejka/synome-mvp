package com.sky.synome.rules;

import com.sky.synome.changeset.ChangesetEventStore;
import com.sky.synome.config.EngineConfig;
import com.sky.synome.core.EngineClockManager;
import com.sky.synome.core.EngineClockManager.ClockMode;
import com.sky.synome.core.EngineSession;
import com.sky.synome.core.FactRegistry.FactEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.kie.api.KieBase;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.rule.EntryPoint;
import org.kie.api.runtime.rule.FactHandle;

@ApplicationScoped
public class RuleHotSwapService {

  private static final int EVENT_REPLAY_BATCH_SIZE = 500;

  @Inject EngineSession engineSession;

  @Inject EngineConfig engineConfig;

  @Inject RuleVersionStore ruleVersionStore;

  @Inject com.sky.synome.checkpoint.CheckpointService checkpointService;

  @Inject ChangesetEventStore changesetEventStore;

  @Inject EngineClockManager engineClockManager;

  public SwapOutcome swapToVersion(UUID targetVersionId, KieBase candidateBase) {
    var lock = engineSession.sessionLock();
    if (!lock.tryAcquire(engineConfig.lockTimeoutMs())) {
      throw new RuleVersionException(
          "LOCK_TIMEOUT",
          503,
          "Engine is busy processing another changeset",
          Map.of("operation", "rule_swap"));
    }

    String phase = "acquire_lock";
    KieBase previousKieBase = null;
    KieSession previousSession = null;
    Map<String, FactEntry> previousRegistry = Map.of();
    KieSession candidateSession = null;
    boolean swapped = false;

    try {
      phase = "create_pre_swap_checkpoint";
      var checkpoint = checkpointService.createCheckpoint("rule-hot-swap-pre");

      previousKieBase = engineSession.kieBase();
      previousSession = engineSession.kieSession();
      previousRegistry = engineSession.snapshotRegistryEntries();
      List<EngineSession.RestorableFact> snapshotFacts = engineSession.snapshotBaseFacts();
      long snapshotClockMillis = engineSession.currentClockMillis();

      phase = "build_candidate_session";
      candidateSession = engineSession.createDetachedSession(candidateBase);
      Map<String, FactEntry> candidateRegistry =
          transferBaseFacts(snapshotFacts, candidateBase, candidateSession);

      phase = "replay_in_window_events";
      int replayedEvents = replayWindowEvents(candidateBase, candidateSession, snapshotClockMillis);

      phase = "converge";
      int convergenceRulesFired = candidateSession.fireAllRules();

      phase = "swap_runtime";
      engineSession.replaceRuntime(candidateBase, candidateSession, candidateRegistry);
      swapped = true;

      phase = "persist_activation";
      RuleVersionStore.ActivationState activationState =
          ruleVersionStore.activate(targetVersionId, Instant.now());

      phase = "dispose_previous_session";
      engineSession.disposeSession(previousSession);

      return new SwapOutcome(
          targetVersionId,
          activationState.previousVersionId(),
          checkpoint.checkpointId(),
          replayedEvents,
          convergenceRulesFired,
          checkpoint.createdAt().toInstant());
    } catch (RuntimeException e) {
      if (swapped) {
        rollbackRuntime(previousKieBase, previousSession, previousRegistry, candidateSession, e);
      } else {
        engineSession.disposeSession(candidateSession);
      }

      throw new RuleVersionException(
          "RULE_ACTIVATION_ERROR",
          400,
          "Rule activation failed in phase=" + phase + ": " + e.getMessage(),
          e,
          Map.of("operation", "rule_swap", "phase", phase, "versionId", targetVersionId));
    } finally {
      lock.release();
    }
  }

  private Map<String, FactEntry> transferBaseFacts(
      List<EngineSession.RestorableFact> snapshotFacts,
      KieBase candidateBase,
      KieSession candidateSession) {
    Map<String, FactEntry> candidateRegistry = new LinkedHashMap<>();
    for (EngineSession.RestorableFact fact : snapshotFacts) {
      Map<String, Object> payload = fact.data() == null ? Map.of() : Map.copyOf(fact.data());
      Object factObject = engineSession.createFact(candidateBase, fact.factType(), payload);
      FactHandle handle = candidateSession.insert(factObject);
      candidateRegistry.put(fact.factKey(), new FactEntry(handle, fact.factType(), payload));
    }
    return candidateRegistry;
  }

  private int replayWindowEvents(
      KieBase candidateBase, KieSession candidateSession, long checkpointClockMillis) {
    if (!engineConfig.eventReplayEnabled()) {
      return 0;
    }

    Duration window = engineConfig.eventReplayWindow();
    long replayStartMillis = Math.max(0L, checkpointClockMillis - Math.max(0L, window.toMillis()));
    Instant replayStart = Instant.ofEpochMilli(replayStartMillis);

    int replayed = 0;
    int offset = 0;
    Set<String> entryPoints = configuredEntrypoints();
    while (true) {
      List<ChangesetEventStore.ProjectedEvent> batch =
          changesetEventStore.listReplayable(
              replayStart, entryPoints, offset, EVENT_REPLAY_BATCH_SIZE);
      if (batch.isEmpty()) {
        break;
      }
      for (ChangesetEventStore.ProjectedEvent event : batch) {
        replayEvent(candidateBase, candidateSession, event);
        replayed++;
      }
      offset += batch.size();
    }
    return replayed;
  }

  private void replayEvent(
      KieBase candidateBase,
      KieSession candidateSession,
      ChangesetEventStore.ProjectedEvent event) {
    Map<String, Object> payload = withEventTimestamp(event.payload(), event.eventTimestamp());
    Object factObject = engineSession.createFact(candidateBase, event.factType(), payload);

    engineClockManager.advanceToEventTime(
        candidateSession, event.eventTimestamp(), ClockMode.REPLAY);
    EntryPoint entryPoint = candidateSession.getEntryPoint(event.entryPoint());
    if (entryPoint == null) {
      throw new IllegalStateException("Unknown entryPoint: " + event.entryPoint());
    }
    entryPoint.insert(factObject);
  }

  private void rollbackRuntime(
      KieBase previousKieBase,
      KieSession previousSession,
      Map<String, FactEntry> previousRegistry,
      KieSession candidateSession,
      RuntimeException original) {
    try {
      engineSession.replaceRuntime(previousKieBase, previousSession, previousRegistry);
    } catch (RuntimeException rollbackFailure) {
      original.addSuppressed(rollbackFailure);
    }
    try {
      engineSession.disposeSession(candidateSession);
    } catch (RuntimeException disposeFailure) {
      original.addSuppressed(disposeFailure);
    }
  }

  private Set<String> configuredEntrypoints() {
    String configured = engineConfig.eventEntrypoints();
    if (configured == null || configured.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(configured.split(","))
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .collect(java.util.stream.Collectors.toSet());
  }

  private Map<String, Object> withEventTimestamp(
      Map<String, Object> payload, Instant eventTimestamp) {
    Map<String, Object> copied = new HashMap<>();
    if (payload != null) {
      copied.putAll(payload);
    }
    copied.put("eventTimestamp", eventTimestamp.toEpochMilli());
    return copied;
  }

  public record SwapOutcome(
      UUID activatedVersionId,
      UUID previousVersionId,
      UUID checkpointId,
      int replayedEvents,
      int convergenceRulesFired,
      Instant activatedAt) {}
}
