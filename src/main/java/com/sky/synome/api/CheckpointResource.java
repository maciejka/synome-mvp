package com.sky.synome.api;

import com.sky.synome.api.dto.CheckpointDetailResponse;
import com.sky.synome.api.dto.CheckpointSummaryResponse;
import com.sky.synome.api.dto.CreateCheckpointResponse;
import com.sky.synome.checkpoint.CheckpointNotFoundException;
import com.sky.synome.checkpoint.CheckpointRecord;
import com.sky.synome.checkpoint.CheckpointService;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;

@Path("/api/v1/checkpoints")
@Produces(MediaType.APPLICATION_JSON)
public class CheckpointResource {

  @Inject CheckpointService checkpointService;

  @POST
  public Response createCheckpoint() {
    CheckpointRecord record = checkpointService.createCheckpoint("manual");
    CreateCheckpointResponse response = toCreateResponse(record);
    return Response.status(Response.Status.CREATED).entity(response).build();
  }

  @GET
  public List<CheckpointSummaryResponse> list(
      @QueryParam("limit") @DefaultValue("20") int limit,
      @QueryParam("offset") @DefaultValue("0") int offset) {
    return checkpointService.list(limit, offset).stream().map(this::toSummary).toList();
  }

  @GET
  @Path("/latest")
  public CheckpointDetailResponse latest() {
    CheckpointRecord record =
        checkpointService
            .findLatest()
            .orElseThrow(() -> new CheckpointNotFoundException("No checkpoints available"));
    return toDetail(record);
  }

  @GET
  @Path("/{id}")
  public CheckpointDetailResponse byId(@PathParam("id") UUID checkpointId) {
    CheckpointRecord record =
        checkpointService
            .findById(checkpointId)
            .orElseThrow(() -> new CheckpointNotFoundException(checkpointId));
    return toDetail(record);
  }

  private CreateCheckpointResponse toCreateResponse(CheckpointRecord record) {
    CreateCheckpointResponse response = new CreateCheckpointResponse();
    fill(response, record);
    response.status = "CREATED";
    return response;
  }

  private CheckpointSummaryResponse toSummary(CheckpointRecord record) {
    CheckpointSummaryResponse response = new CheckpointSummaryResponse();
    fill(response, record);
    return response;
  }

  private CheckpointDetailResponse toDetail(CheckpointRecord record) {
    CheckpointDetailResponse response = new CheckpointDetailResponse();
    fill(response, record);
    return response;
  }

  private void fill(CheckpointSummaryResponse response, CheckpointRecord record) {
    response.checkpointId = record.checkpointId();
    response.sequenceNum = record.sequenceNum();
    response.ruleVersionId = record.ruleVersionId();
    response.clockMillis = record.clockMillis();
    response.factCount = record.factCount();
    response.blobFormat = record.blobFormat();
    response.sizeBytes = record.sizeBytes();
    response.createdAt = record.createdAt();
    response.engineMetadata = record.engineMetadata();
  }
}
