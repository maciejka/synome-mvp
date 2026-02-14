package com.sky.synome.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.sky.synome.api.dto.ErrorResponse;
import com.sky.synome.changeset.DuplicatePayloadMismatchException;
import com.sky.synome.checkpoint.CheckpointException;
import com.sky.synome.checkpoint.CheckpointNotFoundException;
import com.sky.synome.rules.RuleVersionException;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ErrorMapperTest {

  private final ErrorMapper mapper = new ErrorMapper();

  @Test
  void duplicatePayloadMismatchMapsToConflictWithStableCode() {
    UUID changesetId = UUID.randomUUID();

    Response response = mapper.toResponse(new DuplicatePayloadMismatchException(changesetId));
    ErrorResponse body = (ErrorResponse) response.getEntity();

    assertEquals(409, response.getStatus());
    assertNotNull(body);
    assertEquals("DUPLICATE_CHANGESET_PAYLOAD_MISMATCH", body.code());
    assertEquals("changeset_id already exists with different payload", body.message());
    assertEquals(changesetId, body.details().get("changesetId"));
    assertNotNull(body.timestamp());
    assertNotNull(body.requestId());
  }

  @Test
  void checkpointExceptionPreservesDetailsInErrorEnvelope() {
    Response response =
        mapper.toResponse(
            new CheckpointException(
                "Checkpoint creation failed in phase=acquire_lock",
                Map.of("operation", "checkpoint_create", "phase", "acquire_lock")));
    ErrorResponse body = (ErrorResponse) response.getEntity();

    assertEquals(400, response.getStatus());
    assertNotNull(body);
    assertEquals("CHECKPOINT_ERROR", body.code());
    assertEquals("checkpoint_create", body.details().get("operation"));
    assertEquals("acquire_lock", body.details().get("phase"));
  }

  @Test
  void checkpointNotFoundIncludesCheckpointIdWhenProvided() {
    UUID checkpointId = UUID.randomUUID();

    Response response = mapper.toResponse(new CheckpointNotFoundException(checkpointId));
    ErrorResponse body = (ErrorResponse) response.getEntity();

    assertEquals(404, response.getStatus());
    assertNotNull(body);
    assertEquals("CHECKPOINT_NOT_FOUND", body.code());
    assertEquals(checkpointId, body.details().get("checkpointId"));
  }

  @Test
  void checkpointNotFoundWithoutIdKeepsDetailsEmpty() {
    Response response = mapper.toResponse(new CheckpointNotFoundException("No checkpoints"));
    ErrorResponse body = (ErrorResponse) response.getEntity();

    assertEquals(404, response.getStatus());
    assertNotNull(body);
    assertFalse(body.details().containsKey("checkpointId"));
  }

  @Test
  void ruleVersionExceptionMapsToConfiguredStatusAndCode() {
    Response response =
        mapper.toResponse(
            new RuleVersionException(
                "RULE_VALIDATION_ERROR", 400, "Rule validation failed", Map.of("checksum", "abc")));
    ErrorResponse body = (ErrorResponse) response.getEntity();

    assertEquals(400, response.getStatus());
    assertNotNull(body);
    assertEquals("RULE_VALIDATION_ERROR", body.code());
    assertEquals("abc", body.details().get("checksum"));
  }
}
