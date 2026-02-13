package com.sky.synome.checkpoint;

public class CheckpointException extends RuntimeException {

  public CheckpointException(String message) {
    super(message);
  }

  public CheckpointException(String message, Throwable cause) {
    super(message, cause);
  }
}
