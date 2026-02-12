package com.sky.synome.api.dto;

import java.util.List;
import java.util.UUID;

public class ChangesetResponse {

  public UUID changesetId;
  public long sequenceNum;
  public String status;
  public int rulesFired;
  public long durationMs;
  public EffectsSummary effects;
  public List<DerivedFactSummary> newDerivedFacts;
}
