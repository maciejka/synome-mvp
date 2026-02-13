package com.sky.synome.changeset;

import java.util.UUID;

public class DuplicatePayloadMismatchException extends RuntimeException {

  private final UUID changesetId;

  public DuplicatePayloadMismatchException(UUID changesetId) {
    super("changeset_id already exists with different payload");
    this.changesetId = changesetId;
  }

  public UUID changesetId() {
    return changesetId;
  }
}
