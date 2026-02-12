package com.sky.synome.api;

import com.sky.synome.api.dto.FactResponse;
import com.sky.synome.core.EngineSession;
import com.sky.synome.core.FactRegistry;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

@Path("/api/v1/facts")
@Produces(MediaType.APPLICATION_JSON)
public class FactResource {

    @Inject
    EngineSession engineSession;

    @GET
    public List<FactResponse> listFacts(
            @QueryParam("type") String typeFilter) {
        return engineSession.factRegistry().entries().stream()
                .filter(e -> typeFilter == null || e.getValue().factType().equals(typeFilter))
                .map(e -> new FactResponse(e.getKey(), e.getValue().factType(), e.getValue().data()))
                .toList();
    }

    @GET
    @Path("/{factKey}")
    public Response getByKey(@PathParam("factKey") String factKey) {
        FactRegistry.FactEntry entry = engineSession.factRegistry().get(factKey);
        if (entry == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(new FactResponse(factKey, entry.factType(), entry.data())).build();
    }

    @GET
    @Path("/types")
    public List<String> listTypes() {
        return engineSession.kieBase().getKiePackages().stream()
                .flatMap(p -> p.getFactTypes().stream())
                .map(ft -> ft.getName())
                .toList();
    }

    @GET
    @Path("/stats")
    public Map<String, Object> stats() {
        return Map.of(
                "totalFacts", engineSession.factRegistry().size(),
                "countByType", engineSession.factRegistry().countByType()
        );
    }
}
