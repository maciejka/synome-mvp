package com.sky.synome.changeset;

import com.sky.synome.api.dto.ChangesetResponse;
import com.sky.synome.api.dto.DerivedFactSummary;
import com.sky.synome.api.dto.EffectsSummary;
import com.sky.synome.config.EngineConfig;
import com.sky.synome.core.DerivationTracker;
import com.sky.synome.core.EngineSession;
import com.sky.synome.core.FactRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.rule.EntryPoint;
import org.kie.api.runtime.rule.FactHandle;
import org.kie.api.time.SessionPseudoClock;

@ApplicationScoped
public class ChangesetProcessor {

  private static final Logger LOG = Logger.getLogger(ChangesetProcessor.class);

  @Inject EngineSession engineSession;

  @Inject EngineConfig engineConfig;

  @Inject ChangesetValidator validator;

  @Inject ChangesetLog changesetLog;

  public ChangesetResponse process(Changeset changeset) {
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
        ApplyResult applyResult = applyEntriesAndFire(changeset, session, registry);

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
    applyEntriesAndFire(changeset, session, registry);
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
        cause,
        "Changeset %s failed after reservation seq=%d; rolling back session state",
        changesetId,
        reservationSeq);

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
  }

  private ApplyResult applyEntriesAndFire(
      Changeset changeset, KieSession session, FactRegistry registry) {
    EffectsSummary effects = new EffectsSummary();
    DerivationTracker tracker = engineSession.derivationTracker();
    int rulesFired;

    // Start tracking before applying entries to catch TMS retractions
    // triggered by base fact deletions.
    tracker.startTracking();
    try {
      for (ChangesetEntry entry : changeset.entries()) {
        applyEntry(entry, session, registry, effects);
      }
      rulesFired = session.fireAllRules();
    } finally {
      tracker.stopTracking();
    }

    List<DerivedFactSummary> derivedFacts = new ArrayList<>();
    for (DerivationTracker.Derivation derivation : tracker.getDerivations()) {
      effects.derivedFactsCreated++;
      derivedFacts.add(
          new DerivedFactSummary(
              UUID.randomUUID().toString(),
              derivation.factType(),
              derivation.ruleName(),
              derivation.factType() + " derived by " + derivation.ruleName()));
    }
    effects.derivedFactsRetracted = tracker.getRetractions().size();
    return new ApplyResult(rulesFired, effects, derivedFacts);
  }

  private void applyEntry(
      ChangesetEntry entry, KieSession session, FactRegistry registry, EffectsSummary effects) {
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
        applyEmit(entry, session, effects);
      }
    }
  }

  private void applyUpsert(
      ChangesetEntry entry, KieSession session, FactRegistry registry, EffectsSummary effects) {
    Object fact = engineSession.createFact(entry.factType(), entry.data());

    FactRegistry.FactEntry existing = registry.get(entry.factKey());
    if (existing != null) {
      // Update existing fact — mark as explicit delete so tracker ignores it
      engineSession.derivationTracker().markExplicitDelete(existing.handle());
      session.delete(existing.handle());
      FactHandle newHandle = session.insert(fact);
      registry.put(
          entry.factKey(), new FactRegistry.FactEntry(newHandle, entry.factType(), entry.data()));
      effects.factsUpdated++;
    } else {
      // Insert new fact
      FactHandle handle = session.insert(fact);
      registry.put(
          entry.factKey(), new FactRegistry.FactEntry(handle, entry.factType(), entry.data()));
      effects.factsInserted++;
    }
  }

  private void applyDelete(
      ChangesetEntry entry, KieSession session, FactRegistry registry, EffectsSummary effects) {
    FactRegistry.FactEntry existing = registry.remove(entry.factKey());
    if (existing != null) {
      engineSession.derivationTracker().markExplicitDelete(existing.handle());
      session.delete(existing.handle());
      effects.factsDeleted++;
    }
  }

  private void applyEmit(ChangesetEntry entry, KieSession session, EffectsSummary effects) {
    Object event = engineSession.createFact(entry.factType(), entry.data());

    advanceClockForEvent(session, entry.timestamp());
    EntryPoint entryPoint = session.getEntryPoint(entry.entryPoint());
    if (entryPoint == null) {
      throw new IllegalStateException("Unknown entryPoint: " + entry.entryPoint());
    }
    entryPoint.insert(event);
    effects.eventsEmitted++;
  }

  private void advanceClockForEvent(KieSession session, Instant eventTimestamp) {
    if (!(session.getSessionClock() instanceof SessionPseudoClock clock)) {
      throw new IllegalStateException("KieSession is not configured with a pseudo clock");
    }
    long eventMillis = eventTimestamp.toEpochMilli();
    long currentMillis = clock.getCurrentTime();
    if (eventMillis > currentMillis) {
      clock.advanceTime(eventMillis - currentMillis, TimeUnit.MILLISECONDS);
    }
  }

  private record ApplyResult(
      int rulesFired, EffectsSummary effects, List<DerivedFactSummary> derivedFacts) {}

  public static class LockTimeoutException extends RuntimeException {
    public LockTimeoutException(String message) {
      super(message);
    }
  }
}
