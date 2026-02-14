package com.sky.synome.api;

import com.sky.synome.api.dto.DerivedFactSummary;
import com.sky.synome.api.dto.FactResponse;
import com.sky.synome.core.EngineSession;
import com.sky.synome.core.FactRegistry;
import com.sky.synome.provenance.ExplanationService;
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
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;

@Path("/api/v1/facts")
@Produces(MediaType.APPLICATION_JSON)
@RequiresPermission(ApiPermission.FACT_READ)
public class FactResource {

  @Inject EngineSession engineSession;

  @Inject ExplanationService explanationService;

  @GET
  public List<FactResponse> listFacts(@QueryParam("type") String typeFilter) {
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
        "countByType", engineSession.factRegistry().countByType());
  }

  @GET
  @Path("/derived")
  public List<DerivedFactSummary> derived(
      @QueryParam("type") String typeFilter,
      @QueryParam("ruleName") String ruleName,
      @QueryParam("limit") @DefaultValue("50") int limit,
      @QueryParam("offset") @DefaultValue("0") int offset) {
    int safeLimit = Math.max(1, Math.min(limit, 500));
    int safeOffset = Math.max(0, offset);
    return explanationService
        .search(typeFilter, null, ruleName, safeLimit, safeOffset, true)
        .stream()
        .filter(fact -> fact.producedByRule() != null)
        .map(
            fact ->
                new DerivedFactSummary(
                    fact.factId(), fact.factType(), fact.producedByRule(), fact.factKey()))
        .toList();
  }
}
