package com.sky.synome.api.dto;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public class CheckpointSummaryResponse {

  public UUID checkpointId;
  public long sequenceNum;
  public UUID ruleVersionId;
  public long clockMillis;
  public int factCount;
  public String blobFormat;
  public long sizeBytes;
  public OffsetDateTime createdAt;
  public Map<String, Object> engineMetadata;
}
