package com.sky.synome.api;

import com.sky.synome.api.dto.ErrorResponse;
import com.sky.synome.changeset.ChangesetProcessor;
import com.sky.synome.changeset.ChangesetValidator;
import com.sky.synome.changeset.DuplicatePayloadMismatchException;
import com.sky.synome.checkpoint.CheckpointException;
import com.sky.synome.checkpoint.CheckpointNotFoundException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;

@Provider
public class ErrorMapper implements ExceptionMapper<Exception> {

  private static final Logger LOG = Logger.getLogger(ErrorMapper.class);

  @Override
  public Response toResponse(Exception exception) {
    String requestId = UUID.randomUUID().toString();

    if (exception instanceof ChangesetValidator.ValidationException ve) {
      return Response.status(Response.Status.BAD_REQUEST)
          .entity(
              new ErrorResponse(
                  "VALIDATION_ERROR",
                  ve.getMessage(),
                  Map.of("errors", ve.errors()),
                  Instant.now(),
                  requestId))
          .build();
    }

    if (exception instanceof ChangesetProcessor.LockTimeoutException) {
      return Response.status(Response.Status.SERVICE_UNAVAILABLE)
          .entity(
              new ErrorResponse(
                  "LOCK_TIMEOUT",
                  "Engine is busy processing another changeset",
                  Map.of(),
                  Instant.now(),
                  requestId))
          .build();
    }

    if (exception instanceof DuplicatePayloadMismatchException dpe) {
      return Response.status(Response.Status.CONFLICT)
          .entity(
              new ErrorResponse(
                  "DUPLICATE_CHANGESET_PAYLOAD_MISMATCH",
                  dpe.getMessage(),
                  Map.of("changesetId", dpe.changesetId()),
                  Instant.now(),
                  requestId))
          .build();
    }

    if (exception instanceof CheckpointNotFoundException cnf) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(
              new ErrorResponse(
                  "CHECKPOINT_NOT_FOUND", cnf.getMessage(), Map.of(), Instant.now(), requestId))
          .build();
    }

    if (exception instanceof CheckpointException ce) {
      return Response.status(Response.Status.BAD_REQUEST)
          .entity(
              new ErrorResponse(
                  "CHECKPOINT_ERROR", ce.getMessage(), Map.of(), Instant.now(), requestId))
          .build();
    }

    LOG.errorf(exception, "Unhandled exception [requestId=%s]", requestId);
    return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
        .entity(
            new ErrorResponse(
                "INTERNAL_ERROR",
                "An unexpected error occurred",
                Map.of(),
                Instant.now(),
                requestId))
        .build();
  }
}
