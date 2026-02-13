package com.sky.synome.api.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class EventSummaryResponse {

  public long eventId;
  public UUID changesetId;
  public long sequenceNum;
  public String entryPoint;
  public String factType;
  public Instant eventTimestamp;
  public Map<String, Object> payload;
}
