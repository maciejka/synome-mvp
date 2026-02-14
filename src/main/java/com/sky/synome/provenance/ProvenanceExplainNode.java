package com.sky.synome.provenance;

import java.util.List;

public record ProvenanceExplainNode(
    ProvenanceFactRecord fact, List<ProvenanceExplainNode> dependsOn) {

  public ProvenanceExplainNode {
    dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
  }
}
