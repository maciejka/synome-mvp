package com.sky.synome.checkpoint;

import java.util.UUID;

public class CheckpointNotFoundException extends CheckpointException {

  private final UUID checkpointId;

  public CheckpointNotFoundException(UUID checkpointId) {
    super("Checkpoint not found: " + checkpointId);
    this.checkpointId = checkpointId;
  }

  public CheckpointNotFoundException(String message) {
    super(message);
    this.checkpointId = null;
  }

  public UUID checkpointId() {
    return checkpointId;
  }
}
