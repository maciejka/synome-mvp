package com.sky.synome.provenance;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class ExplanationService {

  @Inject ProvenanceGraph graph;

  @Inject ProvenanceRepository repository;

  public Optional<ProvenanceFactRecord> getFact(String factId) {
    Optional<ProvenanceFactRecord> inMemory = graph.findFact(factId);
    if (inMemory.isPresent()) {
      return inMemory;
    }
    return repository.findFactById(factId);
  }

  public List<ProvenanceModification> listModifications(String factId, int limit) {
    int capped = Math.max(1, Math.min(limit, 200));
    List<ProvenanceModification> inMemory = graph.listModifications(factId, capped);
    if (!inMemory.isEmpty()) {
      return inMemory;
    }
    return repository.listModifications(factId, capped);
  }

  public Optional<ProvenanceExplainNode> explain(String factId, int maxDepth) {
    Optional<ProvenanceFactRecord> root = getFact(factId);
    if (root.isEmpty()) {
      return Optional.empty();
    }

    int depth = Math.max(1, Math.min(maxDepth, 25));
    Map<String, ProvenanceFactRecord> cache = new HashMap<>();
    cache.put(factId, root.get());
    return Optional.of(buildExplainNode(root.get(), depth, new HashSet<>(), cache));
  }

  public List<String> impact(String factId, int maxDepth) {
    if (getFact(factId).isEmpty()) {
      return List.of();
    }

    int depth = Math.max(1, Math.min(maxDepth, 25));
    Set<String> visited = new HashSet<>();
    Set<String> frontier = Set.of(factId);
    visited.add(factId);
    List<String> impacted = new ArrayList<>();

    for (int level = 0; level < depth; level++) {
      if (frontier.isEmpty()) {
        break;
      }

      Set<String> next = new HashSet<>();
      for (String current : frontier) {
        Set<String> dependents = new HashSet<>(graph.findDependents(current));
        repository.findDependents(current).stream()
            .map(ProvenanceFactRecord::factId)
            .forEach(dependents::add);

        for (String dependent : dependents) {
          if (visited.add(dependent)) {
            impacted.add(dependent);
            next.add(dependent);
          }
        }
      }
      frontier = next;
    }

    return impacted.stream().sorted().toList();
  }

  public List<ProvenanceFactRecord> search(
      String factType,
      String factKeyPrefix,
      String producedByRule,
      Integer limit,
      Integer offset,
      Boolean activeOnly) {
    int cappedLimit = Math.max(1, Math.min(limit == null ? 50 : limit, 500));
    int safeOffset = Math.max(0, offset == null ? 0 : offset);

    List<ProvenanceFactRecord> persisted =
        repository.search(factType, factKeyPrefix, producedByRule, 1000, 0, activeOnly);

    Set<String> knownFactIds = new HashSet<>();
    for (ProvenanceFactRecord record : persisted) {
      knownFactIds.add(record.factId());
    }

    List<ProvenanceFactRecord> merged = new ArrayList<>(persisted);
    for (ProvenanceFactRecord record : graph.listFacts()) {
      if (knownFactIds.contains(record.factId())) {
        continue;
      }
      if (matchesSearch(record, factType, factKeyPrefix, producedByRule, activeOnly)) {
        merged.add(record);
      }
    }

    List<ProvenanceFactRecord> sorted =
        merged.stream()
            .sorted(
                Comparator.comparing(ProvenanceFactRecord::createdAt)
                    .reversed()
                    .thenComparing(ProvenanceFactRecord::factId))
            .toList();
    int from = Math.min(safeOffset, sorted.size());
    int to = Math.min(from + cappedLimit, sorted.size());
    return sorted.subList(from, to);
  }

  private ProvenanceExplainNode buildExplainNode(
      ProvenanceFactRecord current,
      int depthRemaining,
      Set<String> path,
      Map<String, ProvenanceFactRecord> cache) {
    if (depthRemaining <= 1 || !path.add(current.factId()) || current.inputFactIds().isEmpty()) {
      return new ProvenanceExplainNode(current, List.of());
    }

    List<ProvenanceExplainNode> children = new ArrayList<>();
    for (String inputFactId : current.inputFactIds().stream().sorted().toList()) {
      ProvenanceFactRecord child = resolveFact(inputFactId, cache);
      children.add(buildExplainNode(child, depthRemaining - 1, new HashSet<>(path), cache));
    }
    return new ProvenanceExplainNode(current, children);
  }

  private ProvenanceFactRecord resolveFact(String factId, Map<String, ProvenanceFactRecord> cache) {
    ProvenanceFactRecord cached = cache.get(factId);
    if (cached != null) {
      return cached;
    }

    ProvenanceFactRecord resolved =
        getFact(factId)
            .orElse(
                new ProvenanceFactRecord(
                    factId,
                    "Unknown",
                    null,
                    null,
                    ProvenanceInsertionType.DERIVED_FACT,
                    List.of(),
                    null,
                    Instant.EPOCH,
                    null,
                    null));
    cache.put(factId, resolved);
    return resolved;
  }

  private boolean matchesSearch(
      ProvenanceFactRecord record,
      String factType,
      String factKeyPrefix,
      String producedByRule,
      Boolean activeOnly) {
    if (factType != null && !factType.isBlank() && !factType.equals(record.factType())) {
      return false;
    }
    if (factKeyPrefix != null
        && !factKeyPrefix.isBlank()
        && (record.factKey() == null || !record.factKey().startsWith(factKeyPrefix))) {
      return false;
    }
    if (producedByRule != null
        && !producedByRule.isBlank()
        && !producedByRule.equals(record.producedByRule())) {
      return false;
    }
    if (Boolean.TRUE.equals(activeOnly) && record.retractedAt() != null) {
      return false;
    }
    return true;
  }
}
