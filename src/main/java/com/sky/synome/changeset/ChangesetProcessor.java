package com.sky.synome.changeset;

import com.sky.synome.api.dto.ChangesetResponse;
import com.sky.synome.api.dto.DerivedFactSummary;
import com.sky.synome.api.dto.EffectsSummary;
import com.sky.synome.config.EngineConfig;
import com.sky.synome.core.EngineClockManager;
import com.sky.synome.core.EngineClockManager.ClockMode;
import com.sky.synome.core.EngineSession;
import com.sky.synome.core.FactRegistry;
import com.sky.synome.ops.EngineLifecycle;
import com.sky.synome.ops.EngineWriteUnavailableException;
import com.sky.synome.provenance.ProvenanceCapture;
import com.sky.synome.provenance.ProvenanceCollector;
import com.sky.synome.provenance.ProvenanceGraph;
import com.sky.synome.provenance.ProvenancePersistenceService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.rule.EntryPoint;
import org.kie.api.runtime.rule.FactHandle;

@ApplicationScoped
public class ChangesetProcessor {

  private static final Logger LOG = Logger.getLogger(ChangesetProcessor.class);

  @Inject EngineSession engineSession;

  @Inject EngineConfig engineConfig;

  @Inject ChangesetValidator validator;

  @Inject ChangesetLog changesetLog;

  @Inject ChangesetEventStore changesetEventStore;

  @Inject EngineClockManager engineClockManager;

  @Inject ProvenanceGraph provenanceGraph;

  @Inject ProvenancePersistenceService provenancePersistenceService;

  @Inject EngineLifecycle engineLifecycle;

  public ChangesetResponse process(Changeset changeset) {
    if (!engineLifecycle.acceptsWrites()) {
      throw new EngineWriteUnavailableException("Engine is not accepting writes");
    }

    long start = System.currentTimeMillis();

    var lock = engineSession.sessionLock();
    if (!lock.tryAcquire(engineConfig.lockTimeoutMs())) {
      throw new LockTimeoutException("Could not acquire session lock within timeout");
    }

    try {
      validator.validate(changeset);

      ChangesetResponse replay = preflightDuplicate(changeset);
      if (replay != null) {
        return replay;
      }

      KieSession session = engineSession.kieSession();
      FactRegistry registry = engineSession.factRegistry();
      List<EngineSession.RestorableFact> snapshot = engineSession.snapshotBaseFacts();
      long reservationSeq = changesetLog.reserve(changeset);

      try {
        ApplyResult applyResult = applyEntriesAndFire(changeset, session, registry, ClockMode.LIVE);

        long durationMs = System.currentTimeMillis() - start;

        var response = new ChangesetResponse();
        response.changesetId = changeset.id();
        response.status = "APPLIED";
        response.rulesFired = applyResult.rulesFired();
        response.durationMs = durationMs;
        response.effects = applyResult.effects();
        response.newDerivedFacts = applyResult.derivedFacts();

        changesetLog.finalizeSuccess(
            reservationSeq, applyResult.rulesFired(), durationMs, response);
        changesetEventStore.persistProjectedEvents(
            changeset.id(), reservationSeq, changeset.entries());
        if (!applyResult.provenanceCapture().isEmpty()) {
          provenanceGraph.applyCapture(applyResult.provenanceCapture());
          provenancePersistenceService.enqueue(applyResult.provenanceCapture());
        }
        response.sequenceNum = reservationSeq;

        LOG.infof(
            "Changeset %s applied: seq=%d, rulesFired=%d, derived=%d, retracted=%d, duration=%dms",
            changeset.id(),
            reservationSeq,
            applyResult.rulesFired(),
            applyResult.effects().derivedFactsCreated,
            applyResult.effects().derivedFactsRetracted,
            durationMs);
        return response;
      } catch (RuntimeException e) {
        rollbackAtomicFailure(changeset.id(), reservationSeq, snapshot, e);
        throw e;
      }

    } finally {
      lock.release();
    }
  }

  public void replay(Changeset changeset) {
    validator.validate(changeset);
    KieSession session = engineSession.kieSession();
    FactRegistry registry = engineSession.factRegistry();
    applyEntriesAndFire(changeset, session, registry, ClockMode.REPLAY);
  }

  private ChangesetResponse preflightDuplicate(Changeset changeset) {
    var replay = changesetLog.findReplay(changeset);
    if (replay.isEmpty()) {
      return null;
    }

    var lookup = replay.get();
    if (!lookup.checksumMatches()) {
      throw new DuplicatePayloadMismatchException(changeset.id());
    }
    if (lookup.responsePayload() == null) {
      throw new IllegalStateException("Stored response_payload missing for duplicate changeset");
    }

    LOG.infof(
        "Changeset %s replayed from changeset_log with sequence=%d",
        changeset.id(), lookup.responsePayload().sequenceNum);
    return lookup.responsePayload();
  }

  private void rollbackAtomicFailure(
      UUID changesetId,
      long reservationSeq,
      List<EngineSession.RestorableFact> snapshot,
      RuntimeException cause) {
    LOG.warnf(
        "Changeset %s failed after reservation seq=%d; rolling back session state (%s: %s)",
        changesetId, reservationSeq, cause.getClass().getSimpleName(), cause.getMessage());
    LOG.debugf(cause, "Changeset %s rollback trigger details", changesetId);

    try {
      engineSession.rebuildFromSnapshot(snapshot);
    } catch (RuntimeException rollbackFailure) {
      cause.addSuppressed(rollbackFailure);
    }

    try {
      changesetLog.cancelReservation(reservationSeq);
    } catch (RuntimeException cancelFailure) {
      cause.addSuppressed(cancelFailure);
    }

    try {
      changesetEventStore.deleteBySequence(reservationSeq);
    } catch (RuntimeException projectionRollbackFailure) {
      cause.addSuppressed(projectionRollbackFailure);
    }
  }

  private ApplyResult applyEntriesAndFire(
      Changeset changeset, KieSession session, FactRegistry registry, ClockMode clockMode) {
    EffectsSummary effects = new EffectsSummary();
    ProvenanceCollector collector = engineSession.provenanceCollector();
    Set<String> allowedEntrypoints = configuredEventEntrypoints();
    boolean captureProvenance = clockMode == ClockMode.LIVE;
    ProvenanceCapture provenanceCapture =
        new ProvenanceCapture(changeset.id(), List.of(), List.of(), List.of(), List.of(), 0);
    int rulesFired;

    // Start tracking before applying entries to catch TMS retractions
    // triggered by base fact deletions.
    if (captureProvenance) {
      collector.startChangeset(changeset.id());
    }
    try {
      for (ChangesetEntry entry : changeset.entries()) {
        applyEntry(entry, session, registry, effects, clockMode, allowedEntrypoints);
      }
      rulesFired = session.fireAllRules();
    } finally {
      if (captureProvenance) {
        provenanceCapture = collector.stopAndSnapshot();
      }
    }

    List<DerivedFactSummary> derivedFacts = new ArrayList<>(provenanceCapture.derivedFacts());
    effects.derivedFactsCreated = derivedFacts.size();
    effects.derivedFactsRetracted = provenanceCapture.derivedRetractions();
    return new ApplyResult(rulesFired, effects, derivedFacts, provenanceCapture);
  }

  private void applyEntry(
      ChangesetEntry entry,
      KieSession session,
      FactRegistry registry,
      EffectsSummary effects,
      ClockMode clockMode,
      Set<String> allowedEntrypoints) {
    switch (entry.kind()) {
      case FACT -> {
        switch (entry.action()) {
          case UPSERT -> applyUpsert(entry, session, registry, effects);
          case DELETE -> applyDelete(entry, session, registry, effects);
          default -> throw new IllegalStateException("Unsupported FACT action: " + entry.action());
        }
      }
      case EVENT -> {
        if (entry.action() != ChangesetAction.EMIT) {
          throw new IllegalStateException("Unsupported EVENT action: " + entry.action());
        }
        applyEmit(entry, session, effects, clockMode, allowedEntrypoints);
      }
    }
  }

  private void applyUpsert(
      ChangesetEntry entry, KieSession session, FactRegistry registry, EffectsSummary effects) {
    Object fact = engineSession.createFact(entry.factType(), entry.data());
    ProvenanceCollector collector = engineSession.provenanceCollector();

    FactRegistry.FactEntry existing = registry.get(entry.factKey());
    if (existing != null) {
      // Update existing fact — mark as explicit delete so tracker ignores it
      collector.markExplicitDelete(existing.handle(), false);
      session.delete(existing.handle());
      FactHandle newHandle = session.insert(fact);
      collector.registerBaseFactUpsert(entry.factKey(), entry.factType(), newHandle);
      registry.put(
          entry.factKey(), new FactRegistry.FactEntry(newHandle, entry.factType(), entry.data()));
      effects.factsUpdated++;
    } else {
      // Insert new fact
      FactHandle handle = session.insert(fact);
      collector.registerBaseFactUpsert(entry.factKey(), entry.factType(), handle);
      registry.put(
          entry.factKey(), new FactRegistry.FactEntry(handle, entry.factType(), entry.data()));
      effects.factsInserted++;
    }
  }

  private void applyDelete(
      ChangesetEntry entry, KieSession session, FactRegistry registry, EffectsSummary effects) {
    FactRegistry.FactEntry existing = registry.remove(entry.factKey());
    if (existing != null) {
      engineSession.provenanceCollector().markExplicitDelete(existing.handle(), true);
      session.delete(existing.handle());
      effects.factsDeleted++;
    }
  }

  private void applyEmit(
      ChangesetEntry entry,
      KieSession session,
      EffectsSummary effects,
      ClockMode clockMode,
      Set<String> allowedEntrypoints) {
    if (!allowedEntrypoints.isEmpty() && !allowedEntrypoints.contains(entry.entryPoint())) {
      throw new IllegalStateException("entryPoint is not enabled by engine.event-entrypoints");
    }

    Object event = engineSession.createFact(entry.factType(), withEventTimestamp(entry));

    engineClockManager.advanceToEventTime(session, entry.timestamp(), clockMode);
    EntryPoint entryPoint = session.getEntryPoint(entry.entryPoint());
    if (entryPoint == null) {
      throw new IllegalStateException("Unknown entryPoint: " + entry.entryPoint());
    }
    FactHandle handle = entryPoint.insert(event);
    engineSession.provenanceCollector().registerEventEmission(entry.factType(), handle);
    effects.eventsEmitted++;
  }

  private Set<String> configuredEventEntrypoints() {
    String configured = engineConfig.eventEntrypoints();
    if (configured == null || configured.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(configured.split(","))
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .collect(java.util.stream.Collectors.toSet());
  }

  private java.util.Map<String, Object> withEventTimestamp(ChangesetEntry entry) {
    java.util.Map<String, Object> payload = new HashMap<>();
    if (entry.data() != null) {
      payload.putAll(entry.data());
    }
    payload.put("eventTimestamp", entry.timestamp().toEpochMilli());
    return payload;
  }

  private record ApplyResult(
      int rulesFired,
      EffectsSummary effects,
      List<DerivedFactSummary> derivedFacts,
      ProvenanceCapture provenanceCapture) {}

  public static class LockTimeoutException extends RuntimeException {
    public LockTimeoutException(String message) {
      super(message);
    }
  }
}
