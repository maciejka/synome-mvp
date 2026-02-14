package com.sky.synome.api.dto;

import java.util.List;

public class RuleValidationResponse {
  public boolean valid;
  public boolean compiled;
  public boolean compatible;
  public String checksum;
  public List<String> compilationErrors;
  public List<String> compatibilityErrors;
}
