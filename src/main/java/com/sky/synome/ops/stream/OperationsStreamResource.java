package com.sky.synome.ops.stream;

import com.sky.synome.security.ApiPermission;
import com.sky.synome.security.RequiresPermission;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;

@Path("/api/v1/ops/stream")
@RequiresPermission(ApiPermission.OPS_STREAM_READ)
public class OperationsStreamResource {

  @Inject OperationsStreamService operationsStreamService;

  @GET
  @Path("/changesets")
  @Produces(MediaType.SERVER_SENT_EVENTS)
  @RestStreamElementType(MediaType.APPLICATION_JSON)
  public Multi<ChangesetStreamEvent> changesets(
      @QueryParam("maxEvents") @DefaultValue("0") int maxEvents) {
    return operationsStreamService.changesets(maxEvents);
  }

  @GET
  @Path("/checkpoints")
  @Produces(MediaType.SERVER_SENT_EVENTS)
  @RestStreamElementType(MediaType.APPLICATION_JSON)
  public Multi<CheckpointStreamEvent> checkpoints(
      @QueryParam("maxEvents") @DefaultValue("0") int maxEvents) {
    return operationsStreamService.checkpoints(maxEvents);
  }

  @GET
  @Path("/recovery")
  @Produces(MediaType.SERVER_SENT_EVENTS)
  @RestStreamElementType(MediaType.APPLICATION_JSON)
  public Multi<RecoveryStreamEvent> recovery(
      @QueryParam("maxEvents") @DefaultValue("0") int maxEvents) {
    return operationsStreamService.recovery(maxEvents);
  }
}
