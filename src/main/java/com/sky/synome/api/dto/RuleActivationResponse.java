package com.sky.synome.api.dto;

import java.time.Instant;
import java.util.UUID;

public class RuleActivationResponse {
  public String status;
  public UUID activatedVersionId;
  public UUID previousVersionId;
  public UUID checkpointId;
  public int replayedEvents;
  public int convergenceRulesFired;
  public Instant activatedAt;
}
