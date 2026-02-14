package com.sky.synome.api.dto;

import java.util.Map;

public class RuleValidationRequest {
  public String versionLabel;
  public Map<String, String> drlFiles;
  public String drl;
  public String sourcePath;
  public String uploadedBy;
}
