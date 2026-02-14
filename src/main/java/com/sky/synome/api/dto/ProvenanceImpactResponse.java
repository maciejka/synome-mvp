package com.sky.synome.api.dto;

import java.util.List;

public class ProvenanceImpactResponse {
  public String rootFactId;
  public int maxDepth;
  public List<String> impactedFactIds;
}
