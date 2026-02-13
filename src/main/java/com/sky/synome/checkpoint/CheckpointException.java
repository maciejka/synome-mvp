package com.sky.synome.checkpoint;

import java.util.Map;

public class CheckpointException extends RuntimeException {

  private final Map<String, Object> details;

  public CheckpointException(String message) {
    this(message, null, Map.of());
  }

  public CheckpointException(String message, Throwable cause) {
    this(message, cause, Map.of());
  }

  public CheckpointException(String message, Map<String, Object> details) {
    this(message, null, details);
  }

  public CheckpointException(String message, Throwable cause, Map<String, Object> details) {
    super(message, cause);
    this.details = details == null ? Map.of() : Map.copyOf(details);
  }

  public Map<String, Object> details() {
    return details;
  }
}
