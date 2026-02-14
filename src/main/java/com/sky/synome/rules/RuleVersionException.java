package com.sky.synome.rules;

import java.util.Map;

public class RuleVersionException extends RuntimeException {

  private final String code;
  private final int status;
  private final Map<String, Object> details;

  public RuleVersionException(String code, int status, String message) {
    this(code, status, message, Map.of());
  }

  public RuleVersionException(
      String code, int status, String message, Map<String, Object> details) {
    super(message);
    this.code = code;
    this.status = status;
    this.details = details == null ? Map.of() : Map.copyOf(details);
  }

  public RuleVersionException(
      String code, int status, String message, Throwable cause, Map<String, Object> details) {
    super(message, cause);
    this.code = code;
    this.status = status;
    this.details = details == null ? Map.of() : Map.copyOf(details);
  }

  public String code() {
    return code;
  }

  public int status() {
    return status;
  }

  public Map<String, Object> details() {
    return details;
  }
}
