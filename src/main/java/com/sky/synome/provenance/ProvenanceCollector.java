package com.sky.synome.provenance;

import com.sky.synome.api.dto.DerivedFactSummary;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.kie.api.event.rule.AfterMatchFiredEvent;
import org.kie.api.event.rule.BeforeMatchFiredEvent;
import org.kie.api.event.rule.DefaultAgendaEventListener;
import org.kie.api.event.rule.MatchCancelledEvent;
import org.kie.api.event.rule.ObjectDeletedEvent;
import org.kie.api.event.rule.ObjectInsertedEvent;
import org.kie.api.event.rule.ObjectUpdatedEvent;
import org.kie.api.event.rule.RuleRuntimeEventListener;
import org.kie.api.runtime.rule.FactHandle;

public class ProvenanceCollector extends DefaultAgendaEventListener
    implements RuleRuntimeEventListener {

  private final Map<FactHandle, String> factHandleToFactId = new HashMap<>();
  private final Map<FactHandle, ExplicitDeleteContext> explicitDeletes = new HashMap<>();
  private final Deque<ActivationContext> activationStack = new ArrayDeque<>();
  private final AtomicLong activationCounter = new AtomicLong();

  private final List<ProvenanceFactRecord> factUpserts = new ArrayList<>();
  private final List<ProvenanceRetraction> retractions = new ArrayList<>();
  private final List<ProvenanceModification> modifications = new ArrayList<>();
  private final List<DerivedFactSummary> derivedFacts = new ArrayList<>();

  private UUID activeChangesetId;
  private boolean tracking;
  private int derivedRetractions;

  public synchronized void onSessionReset() {
    factHandleToFactId.clear();
    explicitDeletes.clear();
    activationStack.clear();
    tracking = false;
    activeChangesetId = null;
    clearTransientCapture();
  }

  public synchronized void registerRestoredBaseFact(String factKey, FactHandle handle) {
    factHandleToFactId.put(handle, ProvenanceIdentity.baseFactId(factKey));
  }

  public synchronized void startChangeset(UUID changesetId) {
    tracking = true;
    activeChangesetId = changesetId;
    clearTransientCapture();
    explicitDeletes.clear();
    activationStack.clear();
  }

  public synchronized ProvenanceCapture stopAndSnapshot() {
    tracking = false;
    activationStack.clear();
    explicitDeletes.clear();
    return new ProvenanceCapture(
        activeChangesetId,
        List.copyOf(factUpserts),
        List.copyOf(retractions),
        List.copyOf(modifications),
        List.copyOf(derivedFacts),
        derivedRetractions);
  }

  public synchronized void markExplicitDelete(FactHandle handle, boolean persistRetraction) {
    explicitDeletes.put(handle, new ExplicitDeleteContext(persistRetraction));
  }

  public synchronized void registerBaseFactUpsert(
      String factKey, String factType, FactHandle handle) {
    String factId = ProvenanceIdentity.baseFactId(factKey);
    factHandleToFactId.put(handle, factId);
    if (!tracking) {
      return;
    }
    factUpserts.add(
        new ProvenanceFactRecord(
            factId,
            factType,
            factKey,
            null,
            ProvenanceInsertionType.BASE_FACT,
            List.of(),
            activeChangesetId,
            Instant.now(),
            null,
            null));
  }

  public synchronized void registerEventEmission(String factType, FactHandle handle) {
    String factId = ProvenanceIdentity.eventFactId();
    factHandleToFactId.put(handle, factId);
    if (!tracking) {
      return;
    }
    factUpserts.add(
        new ProvenanceFactRecord(
            factId,
            factType,
            null,
            null,
            ProvenanceInsertionType.EVENT,
            List.of(),
            activeChangesetId,
            Instant.now(),
            null,
            null));
  }

  @Override
  public synchronized void beforeMatchFired(BeforeMatchFiredEvent event) {
    if (!tracking
        || event == null
        || event.getMatch() == null
        || event.getMatch().getRule() == null) {
      return;
    }
    String ruleName = event.getMatch().getRule().getName();
    List<String> inputFactIds =
        event.getMatch().getFactHandles().stream()
            .map(factHandleToFactId::get)
            .filter(id -> id != null && !id.isBlank())
            .distinct()
            .sorted()
            .toList();
    activationStack.push(
        new ActivationContext(
            "activation:" + activeChangesetId + ":" + activationCounter.incrementAndGet(),
            ruleName,
            inputFactIds));
  }

  @Override
  public synchronized void afterMatchFired(AfterMatchFiredEvent event) {
    if (!tracking || activationStack.isEmpty()) {
      return;
    }
    activationStack.pop();
  }

  @Override
  public synchronized void matchCancelled(MatchCancelledEvent event) {
    if (!tracking || activationStack.isEmpty()) {
      return;
    }
    activationStack.pop();
  }

  @Override
  public synchronized void objectInserted(ObjectInsertedEvent event) {
    if (!tracking || event == null || event.getRule() == null) {
      return;
    }

    String ruleName = event.getRule().getName();
    String factId = ProvenanceIdentity.derivedFactId();
    String factType =
        event.getObject() == null ? "Unknown" : event.getObject().getClass().getSimpleName();
    List<String> inputFactIds = resolveInputFactIds(ruleName);

    factHandleToFactId.put(event.getFactHandle(), factId);
    factUpserts.add(
        new ProvenanceFactRecord(
            factId,
            factType,
            null,
            ruleName,
            ProvenanceInsertionType.DERIVED_FACT,
            inputFactIds,
            activeChangesetId,
            Instant.now(),
            null,
            null));
    derivedFacts.add(
        new DerivedFactSummary(factId, factType, ruleName, factType + " derived by " + ruleName));
  }

  @Override
  public synchronized void objectDeleted(ObjectDeletedEvent event) {
    if (!tracking || event == null) {
      return;
    }

    FactHandle handle = event.getFactHandle();
    ExplicitDeleteContext explicitDeleteContext = explicitDeletes.remove(handle);
    String factId = factHandleToFactId.remove(handle);
    if (factId == null) {
      return;
    }

    if (explicitDeleteContext != null && !explicitDeleteContext.persistRetraction()) {
      return;
    }

    String ruleName = event.getRule() != null ? event.getRule().getName() : "TMS";
    String reason =
        explicitDeleteContext != null
            ? "EXPLICIT_DELETE"
            : event.getRule() != null ? "RULE_RETRACTION" : "TMS_RETRACTION";

    retractions.add(new ProvenanceRetraction(factId, ruleName, Instant.now(), reason));
    if (ProvenanceIdentity.isDerived(factId)) {
      derivedRetractions++;
    }
  }

  @Override
  public synchronized void objectUpdated(ObjectUpdatedEvent event) {
    if (!tracking || event == null || event.getRule() == null) {
      return;
    }

    FactHandle handle = event.getFactHandle();
    String factId = factHandleToFactId.get(handle);
    if (factId == null) {
      factId = ProvenanceIdentity.derivedFactId();
      factHandleToFactId.put(handle, factId);
    }

    modifications.add(
        new ProvenanceModification(
            factId,
            event.getRule().getName(),
            Instant.now(),
            extractState(event.getOldObject()),
            extractState(event.getObject()),
            resolveInputFactIds(event.getRule().getName())));
  }

  private List<String> resolveInputFactIds(String ruleName) {
    if (activationStack.isEmpty()) {
      return List.of();
    }
    return activationStack.stream()
        .filter(context -> context.ruleName().equals(ruleName))
        .findFirst()
        .map(ActivationContext::inputFactIds)
        .orElse(List.of());
  }

  private Map<String, Object> extractState(Object fact) {
    if (fact == null) {
      return Map.of();
    }
    if (fact instanceof Map<?, ?> source) {
      Map<String, Object> mapped = new HashMap<>();
      for (Map.Entry<?, ?> entry : source.entrySet()) {
        if (entry.getKey() != null) {
          mapped.put(entry.getKey().toString(), entry.getValue());
        }
      }
      return mapped;
    }

    Map<String, Object> state = new HashMap<>();
    try {
      for (PropertyDescriptor descriptor :
          Introspector.getBeanInfo(fact.getClass(), Object.class).getPropertyDescriptors()) {
        Method readMethod = descriptor.getReadMethod();
        if (readMethod == null) {
          continue;
        }
        Object value = readMethod.invoke(fact);
        state.put(descriptor.getName(), value);
      }
    } catch (IntrospectionException
        | IllegalAccessException
        | InvocationTargetException
        | RuntimeException ignored) {
      state.put("value", String.valueOf(fact));
    }

    return state.entrySet().stream()
        .sorted(Comparator.comparing(Map.Entry::getKey))
        .collect(
            java.util.stream.Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue,
                (left, right) -> left,
                java.util.LinkedHashMap::new));
  }

  private void clearTransientCapture() {
    factUpserts.clear();
    retractions.clear();
    modifications.clear();
    derivedFacts.clear();
    derivedRetractions = 0;
  }

  private record ExplicitDeleteContext(boolean persistRetraction) {}

  private record ActivationContext(
      String activationId, String ruleName, List<String> inputFactIds) {}
}
