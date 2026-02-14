package com.sky.synome.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public class RuleVersionSummaryResponse {
  public UUID versionId;
  public String versionLabel;
  public String checksum;
  public String uploadedBy;
  public OffsetDateTime uploadedAt;
  public OffsetDateTime activatedAt;
  public OffsetDateTime deactivatedAt;
  public boolean active;
}
