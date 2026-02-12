package com.sky.synome.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.kie.api.event.rule.ObjectDeletedEvent;
import org.kie.api.event.rule.ObjectInsertedEvent;
import org.kie.api.event.rule.ObjectUpdatedEvent;
import org.kie.api.event.rule.RuleRuntimeEventListener;
import org.kie.api.runtime.rule.FactHandle;

public class DerivationTracker implements RuleRuntimeEventListener {

  public record Derivation(Object fact, String ruleName, String factType) {}

  public record Retraction(Object fact, String ruleName, String factType) {}

  private volatile boolean tracking = false;
  private final List<Derivation> derivations = new ArrayList<>();
  private final List<Retraction> retractions = new ArrayList<>();
  private final Set<FactHandle> explicitDeletes = new HashSet<>();

  public void startTracking() {
    derivations.clear();
    retractions.clear();
    explicitDeletes.clear();
    tracking = true;
  }

  public void stopTracking() {
    tracking = false;
  }

  /**
   * Mark a fact handle as an explicit delete (base fact removal by the processor). These will be
   * excluded from TMS retraction tracking.
   */
  public void markExplicitDelete(FactHandle handle) {
    explicitDeletes.add(handle);
  }

  public List<Derivation> getDerivations() {
    return List.copyOf(derivations);
  }

  public List<Retraction> getRetractions() {
    return List.copyOf(retractions);
  }

  @Override
  public void objectInserted(ObjectInsertedEvent event) {
    if (tracking && event.getRule() != null) {
      Object fact = event.getObject();
      String ruleName = event.getRule().getName();
      String factType = fact.getClass().getSimpleName();
      derivations.add(new Derivation(fact, ruleName, factType));
    }
  }

  @Override
  public void objectDeleted(ObjectDeletedEvent event) {
    if (!tracking) {
      return;
    }

    FactHandle handle = event.getFactHandle();
    // Skip explicit base fact deletions done by the processor
    if (explicitDeletes.contains(handle)) {
      return;
    }

    Object fact = event.getOldObject();
    String ruleName = event.getRule() != null ? event.getRule().getName() : "TMS";
    String factType = fact.getClass().getSimpleName();
    retractions.add(new Retraction(fact, ruleName, factType));
  }

  @Override
  public void objectUpdated(ObjectUpdatedEvent event) {
    // No-op for Phase 1
  }
}
