package com.sky.synome.provenance;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ProvenanceModification(
    String factId,
    String ruleName,
    Instant modifiedAt,
    Map<String, Object> beforeState,
    Map<String, Object> afterState,
    List<String> triggeringFacts) {

  public ProvenanceModification {
    beforeState = beforeState == null ? Map.of() : Map.copyOf(beforeState);
    afterState = afterState == null ? Map.of() : Map.copyOf(afterState);
    triggeringFacts = triggeringFacts == null ? List.of() : List.copyOf(triggeringFacts);
  }
}
