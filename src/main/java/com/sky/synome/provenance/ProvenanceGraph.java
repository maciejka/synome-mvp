package com.sky.synome.provenance;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class ProvenanceGraph {

  private final Map<String, ProvenanceFactRecord> facts = new HashMap<>();
  private final Map<String, List<ProvenanceModification>> modificationsByFactId = new HashMap<>();
  private final Map<String, Set<String>> inputsByFactId = new HashMap<>();
  private final Map<String, Set<String>> dependentsByInputId = new HashMap<>();

  public synchronized void applyCapture(ProvenanceCapture capture) {
    for (ProvenanceFactRecord factRecord : capture.factUpserts()) {
      upsertFact(factRecord);
    }
    for (ProvenanceRetraction retraction : capture.retractions()) {
      markRetracted(retraction);
    }
    for (ProvenanceModification modification : capture.modifications()) {
      modificationsByFactId
          .computeIfAbsent(modification.factId(), key -> new ArrayList<>())
          .add(modification);
    }
  }

  public synchronized Optional<ProvenanceFactRecord> findFact(String factId) {
    return Optional.ofNullable(facts.get(factId));
  }

  public synchronized List<ProvenanceFactRecord> findFacts(Collection<String> factIds) {
    return factIds.stream().map(facts::get).filter(record -> record != null).toList();
  }

  public synchronized List<ProvenanceFactRecord> listFacts() {
    return new ArrayList<>(facts.values());
  }

  public synchronized List<ProvenanceModification> listModifications(String factId, int limit) {
    List<ProvenanceModification> modifications =
        modificationsByFactId.getOrDefault(factId, List.of());
    int capped = Math.max(0, Math.min(limit, modifications.size()));
    return modifications.subList(0, capped);
  }

  public synchronized Set<String> findDependents(String factId) {
    return Set.copyOf(dependentsByInputId.getOrDefault(factId, Set.of()));
  }

  private void upsertFact(ProvenanceFactRecord record) {
    Set<String> previousInputs = inputsByFactId.get(record.factId());
    if (previousInputs != null) {
      for (String inputFactId : previousInputs) {
        Set<String> dependents = dependentsByInputId.get(inputFactId);
        if (dependents == null) {
          continue;
        }
        dependents.remove(record.factId());
        if (dependents.isEmpty()) {
          dependentsByInputId.remove(inputFactId);
        }
      }
    }

    Set<String> newInputs = new HashSet<>(record.inputFactIds());
    inputsByFactId.put(record.factId(), newInputs);
    for (String inputFactId : newInputs) {
      dependentsByInputId.computeIfAbsent(inputFactId, key -> new HashSet<>()).add(record.factId());
    }

    facts.put(
        record.factId(),
        new ProvenanceFactRecord(
            record.factId(),
            record.factType(),
            record.factKey(),
            record.producedByRule(),
            record.insertionType(),
            record.inputFactIds(),
            record.changesetId(),
            record.createdAt(),
            null,
            null));
  }

  private void markRetracted(ProvenanceRetraction retraction) {
    ProvenanceFactRecord existing = facts.get(retraction.factId());
    if (existing == null) {
      facts.put(
          retraction.factId(),
          new ProvenanceFactRecord(
              retraction.factId(),
              "Unknown",
              null,
              retraction.ruleName(),
              ProvenanceInsertionType.DERIVED_FACT,
              List.of(),
              null,
              retraction.retractedAt(),
              retraction.retractedAt(),
              retraction.retractionReason()));
      return;
    }

    facts.put(
        retraction.factId(),
        existing.withRetraction(
            retraction.retractedAt() == null ? Instant.now() : retraction.retractedAt(),
            retraction.retractionReason()));
  }
}
