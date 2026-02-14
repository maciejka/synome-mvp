package com.sky.synome.provenance;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProvenanceFactRecord(
    String factId,
    String factType,
    String factKey,
    String producedByRule,
    ProvenanceInsertionType insertionType,
    List<String> inputFactIds,
    UUID changesetId,
    Instant createdAt,
    Instant retractedAt,
    String retractionReason) {

  public ProvenanceFactRecord {
    inputFactIds = inputFactIds == null ? List.of() : List.copyOf(inputFactIds);
  }

  public ProvenanceFactRecord withRetraction(
      Instant retractedAtValue, String retractionReasonValue) {
    return new ProvenanceFactRecord(
        factId,
        factType,
        factKey,
        producedByRule,
        insertionType,
        inputFactIds,
        changesetId,
        createdAt,
        retractedAtValue,
        retractionReasonValue);
  }
}
