package com.sky.synome.api;

import com.sky.synome.api.dto.ChangesetRequest;
import com.sky.synome.api.dto.ChangesetResponse;
import com.sky.synome.changeset.ChangesetProcessor;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jooq.DSLContext;
import org.jooq.Record;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

@Path("/api/v1/changesets")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ChangesetResource {

    @Inject
    ChangesetProcessor processor;

    @Inject
    DSLContext dsl;

    @POST
    public Response apply(ChangesetRequest request) {
        ChangesetResponse response = processor.process(request.toChangeset());
        return Response.status(Response.Status.CREATED).entity(response).build();
    }

    @GET
    public List<Map<String, Object>> list(
            @QueryParam("limit") @DefaultValue("50") int limit,
            @QueryParam("offset") @DefaultValue("0") int offset) {
        return dsl.select(
                        field("sequence_num"),
                        field("changeset_id"),
                        field("applied_at"),
                        field("rules_fired"),
                        field("duration_ms"),
                        field("checksum")
                )
                .from(table("changeset_log"))
                .orderBy(field("sequence_num").desc())
                .limit(limit)
                .offset(offset)
                .fetch()
                .map(r -> Map.<String, Object>of(
                        "sequenceNum", r.get(field("sequence_num")),
                        "changesetId", r.get(field("changeset_id")),
                        "appliedAt", r.get(field("applied_at")).toString(),
                        "rulesFired", r.get(field("rules_fired")),
                        "durationMs", r.get(field("duration_ms")),
                        "checksum", r.get(field("checksum"))
                ));
    }

    @GET
    @Path("/{id}")
    public Response getById(@PathParam("id") UUID id) {
        Record record = dsl.select()
                .from(table("changeset_log"))
                .where(field("changeset_id").eq(id))
                .fetchOne();

        if (record == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        return Response.ok(Map.of(
                "sequenceNum", record.get(field("sequence_num")),
                "changesetId", record.get(field("changeset_id")),
                "payload", record.get(field("payload")).toString(),
                "checksum", record.get(field("checksum")),
                "appliedAt", record.get(field("applied_at")).toString(),
                "rulesFired", record.get(field("rules_fired")),
                "durationMs", record.get(field("duration_ms"))
        )).build();
    }
}
