package com.sky.synome.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.sky.synome.api.dto.ErrorResponse;
import com.sky.synome.changeset.DuplicatePayloadMismatchException;
import jakarta.ws.rs.core.Response;
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
}
