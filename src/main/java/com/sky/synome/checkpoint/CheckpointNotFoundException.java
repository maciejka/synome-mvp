package com.sky.synome.checkpoint;

import java.util.Map;
import java.util.UUID;

public class CheckpointNotFoundException extends CheckpointException {

  private final UUID checkpointId;

  public CheckpointNotFoundException(UUID checkpointId) {
    super("Checkpoint not found: " + checkpointId, Map.of("checkpointId", checkpointId.toString()));
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
