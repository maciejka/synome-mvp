package com.sky.synome.api;

import com.sky.synome.api.dto.EventSummaryResponse;
import com.sky.synome.changeset.ChangesetEventStore;
import com.sky.synome.security.ApiPermission;
import com.sky.synome.security.RequiresPermission;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Path("/api/v1/events")
@Produces(MediaType.APPLICATION_JSON)
@RequiresPermission(ApiPermission.FACT_READ)
public class EventResource {

  private static final int MAX_LIMIT = 500;

  @Inject ChangesetEventStore changesetEventStore;

  @GET
  public List<EventSummaryResponse> listEvents(
      @QueryParam("entryPoint") String entryPoint,
      @QueryParam("from") Instant from,
      @QueryParam("to") Instant to,
      @QueryParam("limit") @DefaultValue("50") int limit,
      @QueryParam("offset") @DefaultValue("0") int offset) {
    return changesetEventStore
        .listEvents(entryPoint, from, to, normalizeLimit(limit), normalizeOffset(offset), null)
        .stream()
        .map(this::toResponse)
        .toList();
  }

  @GET
  @Path("/{changesetId}")
  public List<EventSummaryResponse> listByChangesetId(
      @PathParam("changesetId") UUID changesetId,
      @QueryParam("entryPoint") String entryPoint,
      @QueryParam("from") Instant from,
      @QueryParam("to") Instant to,
      @QueryParam("limit") @DefaultValue("50") int limit,
      @QueryParam("offset") @DefaultValue("0") int offset) {
    return changesetEventStore
        .listEvents(
            entryPoint, from, to, normalizeLimit(limit), normalizeOffset(offset), changesetId)
        .stream()
        .map(this::toResponse)
        .toList();
  }

  private int normalizeLimit(int limit) {
    if (limit <= 0) {
      return 50;
    }
    return Math.min(limit, MAX_LIMIT);
  }

  private int normalizeOffset(int offset) {
    return Math.max(offset, 0);
  }

  private EventSummaryResponse toResponse(ChangesetEventStore.ProjectedEvent event) {
    EventSummaryResponse response = new EventSummaryResponse();
    response.eventId = event.eventId();
    response.changesetId = event.changesetId();
    response.sequenceNum = event.sequenceNum();
    response.entryPoint = event.entryPoint();
    response.factType = event.factType();
    response.eventTimestamp = event.eventTimestamp();
    response.payload = event.payload();
    return response;
  }
}
