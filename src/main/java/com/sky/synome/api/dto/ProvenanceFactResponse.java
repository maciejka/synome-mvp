package com.sky.synome.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class ProvenanceFactResponse {
  public String factId;
  public String factType;
  public String factKey;
  public String producedByRule;
  public String insertionType;
  public List<String> inputFactIds;
  public UUID changesetId;
  public Instant createdAt;
  public Instant retractedAt;
  public String retractionReason;
  public List<ProvenanceModificationResponse> modifications;
}
