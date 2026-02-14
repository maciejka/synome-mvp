package com.sky.synome.provenance;

import com.sky.synome.api.dto.DerivedFactSummary;
import java.util.List;
import java.util.UUID;

public record ProvenanceCapture(
    UUID changesetId,
    List<ProvenanceFactRecord> factUpserts,
    List<ProvenanceRetraction> retractions,
    List<ProvenanceModification> modifications,
    List<DerivedFactSummary> derivedFacts,
    int derivedRetractions) {

  public ProvenanceCapture {
    factUpserts = factUpserts == null ? List.of() : List.copyOf(factUpserts);
    retractions = retractions == null ? List.of() : List.copyOf(retractions);
    modifications = modifications == null ? List.of() : List.copyOf(modifications);
    derivedFacts = derivedFacts == null ? List.of() : List.copyOf(derivedFacts);
  }

  public boolean isEmpty() {
    return factUpserts.isEmpty() && retractions.isEmpty() && modifications.isEmpty();
  }
}
