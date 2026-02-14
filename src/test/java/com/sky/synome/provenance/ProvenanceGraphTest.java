package com.sky.synome.provenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sky.synome.api.dto.DerivedFactSummary;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProvenanceGraphTest {

  @Test
  void applyCaptureTracksDependenciesRetractionsAndModifications() {
    ProvenanceGraph graph = new ProvenanceGraph();
    Instant now = Instant.now();

    String baseFactId = ProvenanceIdentity.baseFactId("customer:C-graph-1");
    String derivedFactId = "derived:test-1";
    UUID changesetId = UUID.randomUUID();

    ProvenanceFactRecord baseFact =
        new ProvenanceFactRecord(
            baseFactId,
            "Customer",
            "customer:C-graph-1",
            null,
            ProvenanceInsertionType.BASE_FACT,
            List.of(),
            changesetId,
            now,
            null,
            null);
    ProvenanceFactRecord derivedFact =
        new ProvenanceFactRecord(
            derivedFactId,
            "HighValueCustomer",
            null,
            "Premium customer with high-value account",
            ProvenanceInsertionType.DERIVED_FACT,
            List.of(baseFactId),
            changesetId,
            now,
            null,
            null);

    ProvenanceCapture capture =
        new ProvenanceCapture(
            changesetId,
            List.of(baseFact, derivedFact),
            List.of(new ProvenanceRetraction(derivedFactId, "TMS", now, "TMS_RETRACTION")),
            List.of(
                new ProvenanceModification(
                    derivedFactId,
                    "RuleA",
                    now,
                    java.util.Map.of("before", 1),
                    java.util.Map.of("after", 2),
                    List.of(baseFactId))),
            List.of(new DerivedFactSummary(derivedFactId, "HighValueCustomer", "RuleA", "summary")),
            1);

    graph.applyCapture(capture);

    ProvenanceFactRecord loaded = graph.findFact(derivedFactId).orElseThrow();
    assertNotNull(loaded.retractedAt());
    assertEquals("TMS_RETRACTION", loaded.retractionReason());
    assertTrue(graph.findDependents(baseFactId).contains(derivedFactId));
    assertEquals(1, graph.listModifications(derivedFactId, 10).size());
  }
}
