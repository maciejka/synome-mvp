package com.sky.synome.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public class ProvenanceModificationResponse {
  public String factId;
  public String ruleName;
  public Instant modifiedAt;
  public Map<String, Object> beforeState;
  public Map<String, Object> afterState;
  public List<String> triggeringFacts;
}
