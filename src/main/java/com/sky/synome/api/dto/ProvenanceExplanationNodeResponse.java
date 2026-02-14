package com.sky.synome.api.dto;

import java.time.Instant;
import java.util.List;

public class ProvenanceExplanationNodeResponse {
  public String factId;
  public String factType;
  public String factKey;
  public String producedByRule;
  public String insertionType;
  public List<String> inputFactIds;
  public Instant createdAt;
  public Instant retractedAt;
  public String retractionReason;
  public List<ProvenanceExplanationNodeResponse> dependsOn;
}
