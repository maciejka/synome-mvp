package com.sky.synome.changeset;

import com.sky.synome.api.dto.ChangesetResponse;
import com.sky.synome.api.dto.DerivedFactSummary;
import com.sky.synome.api.dto.EffectsSummary;
import com.sky.synome.core.DerivationTracker;
import com.sky.synome.core.EngineSession;
import com.sky.synome.core.FactRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;
import org.kie.api.definition.type.FactType;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.rule.FactHandle;

@ApplicationScoped
public class ChangesetProcessor {

  private static final Logger LOG = Logger.getLogger(ChangesetProcessor.class);
  private static final String DRL_PACKAGE = "com.sky.synome.rules";

  @Inject EngineSession engineSession;

  @Inject ChangesetValidator validator;

  @Inject ChangesetLog changesetLog;

  public ChangesetResponse process(Changeset changeset) {
    long start = System.currentTimeMillis();

    var lock = engineSession.sessionLock();
    if (!lock.tryAcquire(5000)) {
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
      List<EngineSession.RestorableFact> snapshot = snapshotBaseFacts(session, registry);
      long reservationSeq = changesetLog.reserve(changeset);

      try {
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

        // Collect derived facts.
        List<DerivedFactSummary> derivedFacts = new ArrayList<>();
        for (DerivationTracker.Derivation d : tracker.getDerivations()) {
          effects.derivedFactsCreated++;
          derivedFacts.add(
              new DerivedFactSummary(
                  UUID.randomUUID().toString(),
                  d.factType(),
                  d.ruleName(),
                  d.factType() + " derived by " + d.ruleName()));
        }
        effects.derivedFactsRetracted = tracker.getRetractions().size();

        long durationMs = System.currentTimeMillis() - start;

        var response = new ChangesetResponse();
        response.changesetId = changeset.id();
        response.status = "APPLIED";
        response.rulesFired = rulesFired;
        response.durationMs = durationMs;
        response.effects = effects;
        response.newDerivedFacts = derivedFacts;

        changesetLog.finalizeSuccess(reservationSeq, rulesFired, durationMs, response);
        response.sequenceNum = reservationSeq;

        LOG.infof(
            "Changeset %s applied: seq=%d, rulesFired=%d, derived=%d, retracted=%d, duration=%dms",
            changeset.id(),
            reservationSeq,
            rulesFired,
            effects.derivedFactsCreated,
            effects.derivedFactsRetracted,
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
        changeset.id(),
        lookup.responsePayload().sequenceNum);
    return lookup.responsePayload();
  }

  private List<EngineSession.RestorableFact> snapshotBaseFacts(
      KieSession session, FactRegistry registry) {
    List<EngineSession.RestorableFact> snapshot = new ArrayList<>();
    for (Map.Entry<String, FactRegistry.FactEntry> entry : registry.entries()) {
      FactRegistry.FactEntry factEntry = entry.getValue();
      Object factObject = session.getObject(factEntry.handle());
      if (factObject == null) {
        continue;
      }
      Map<String, Object> dataSnapshot =
          factEntry.data() == null ? Map.of() : Map.copyOf(factEntry.data());
      snapshot.add(
          new EngineSession.RestorableFact(
              entry.getKey(), factEntry.factType(), dataSnapshot, factObject));
    }
    return snapshot;
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

  private void applyEntry(
      ChangesetEntry entry, KieSession session, FactRegistry registry, EffectsSummary effects) {
    switch (entry.action()) {
      case UPSERT -> applyUpsert(entry, session, registry, effects);
      case DELETE -> applyDelete(entry, session, registry, effects);
      case EMIT -> applyEmit(entry, session, effects);
    }
  }

  private void applyUpsert(
      ChangesetEntry entry, KieSession session, FactRegistry registry, EffectsSummary effects) {
    FactType factType = engineSession.kieBase().getFactType(DRL_PACKAGE, entry.factType());
    Object fact = createFact(factType, entry.data());

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
    FactType factType = engineSession.kieBase().getFactType(DRL_PACKAGE, entry.factType());
    Object event = createFact(factType, entry.data());
    session.insert(event);
    effects.eventsEmitted++;
  }

  private Object createFact(FactType factType, Map<String, Object> data) {
    try {
      Object instance = factType.newInstance();
      for (Map.Entry<String, Object> field : data.entrySet()) {
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

  private Object coerceValue(Object value, Class<?> targetType) {
    if (value == null) {
      return null;
    }
    if (targetType.isInstance(value)) {
      return value;
    }

    // Handle numeric coercion from JSON (Jackson may parse numbers as Integer/Long)
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

  public static class LockTimeoutException extends RuntimeException {
    public LockTimeoutException(String message) {
      super(message);
    }
  }
}
