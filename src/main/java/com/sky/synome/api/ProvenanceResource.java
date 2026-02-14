package com.sky.synome.api;

import com.sky.synome.api.dto.ProvenanceExplainResponse;
import com.sky.synome.api.dto.ProvenanceExplanationNodeResponse;
import com.sky.synome.api.dto.ProvenanceFactResponse;
import com.sky.synome.api.dto.ProvenanceImpactResponse;
import com.sky.synome.api.dto.ProvenanceModificationResponse;
import com.sky.synome.provenance.ExplanationService;
import com.sky.synome.provenance.ProvenanceExplainNode;
import com.sky.synome.provenance.ProvenanceFactRecord;
import com.sky.synome.provenance.ProvenanceModification;
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

@Path("/api/v1/provenance")
@Produces(MediaType.APPLICATION_JSON)
public class ProvenanceResource {

  @Inject ExplanationService explanationService;

  @GET
  @Path("/facts/{factId}")
  public Response getFact(
      @PathParam("factId") String factId,
      @QueryParam("modLimit") @DefaultValue("50") int modLimit) {
    return explanationService
        .getFact(factId)
        .map(
            fact -> {
              ProvenanceFactResponse response = toFactResponse(fact);
              response.modifications =
                  explanationService.listModifications(factId, normalizeLimit(modLimit)).stream()
                      .map(this::toModificationResponse)
                      .toList();
              return Response.ok(response).build();
            })
        .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
  }

  @GET
  @Path("/facts/{factId}/explain")
  public Response explain(
      @PathParam("factId") String factId, @QueryParam("maxDepth") @DefaultValue("5") int maxDepth) {
    return explanationService
        .explain(factId, normalizeDepth(maxDepth))
        .map(
            root -> {
              ProvenanceExplainResponse response = new ProvenanceExplainResponse();
              response.rootFactId = factId;
              response.maxDepth = normalizeDepth(maxDepth);
              response.explanation = toExplanationNodeResponse(root);
              return Response.ok(response).build();
            })
        .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
  }

  @GET
  @Path("/facts/{factId}/impact")
  public Response impact(
      @PathParam("factId") String factId, @QueryParam("maxDepth") @DefaultValue("3") int maxDepth) {
    if (explanationService.getFact(factId).isEmpty()) {
      return Response.status(Response.Status.NOT_FOUND).build();
    }

    ProvenanceImpactResponse response = new ProvenanceImpactResponse();
    response.rootFactId = factId;
    response.maxDepth = normalizeDepth(maxDepth);
    response.impactedFactIds = explanationService.impact(factId, response.maxDepth);
    return Response.ok(response).build();
  }

  @GET
  @Path("/search")
  public List<ProvenanceFactResponse> search(
      @QueryParam("factType") String factType,
      @QueryParam("factKey") String factKeyPrefix,
      @QueryParam("ruleName") String producedByRule,
      @QueryParam("activeOnly") @DefaultValue("false") boolean activeOnly,
      @QueryParam("limit") @DefaultValue("50") int limit,
      @QueryParam("offset") @DefaultValue("0") int offset) {
    return explanationService
        .search(
            factType,
            factKeyPrefix,
            producedByRule,
            normalizeLimit(limit),
            Math.max(offset, 0),
            activeOnly)
        .stream()
        .map(this::toFactResponse)
        .toList();
  }

  private int normalizeLimit(int limit) {
    return Math.max(1, Math.min(limit, 500));
  }

  private int normalizeDepth(int maxDepth) {
    return Math.max(1, Math.min(maxDepth, 25));
  }

  private ProvenanceFactResponse toFactResponse(ProvenanceFactRecord fact) {
    ProvenanceFactResponse response = new ProvenanceFactResponse();
    response.factId = fact.factId();
    response.factType = fact.factType();
    response.factKey = fact.factKey();
    response.producedByRule = fact.producedByRule();
    response.insertionType = fact.insertionType().name();
    response.inputFactIds = fact.inputFactIds();
    response.changesetId = fact.changesetId();
    response.createdAt = fact.createdAt();
    response.retractedAt = fact.retractedAt();
    response.retractionReason = fact.retractionReason();
    response.modifications = List.of();
    return response;
  }

  private ProvenanceModificationResponse toModificationResponse(
      ProvenanceModification modification) {
    ProvenanceModificationResponse response = new ProvenanceModificationResponse();
    response.factId = modification.factId();
    response.ruleName = modification.ruleName();
    response.modifiedAt = modification.modifiedAt();
    response.beforeState = modification.beforeState();
    response.afterState = modification.afterState();
    response.triggeringFacts = modification.triggeringFacts();
    return response;
  }

  private ProvenanceExplanationNodeResponse toExplanationNodeResponse(ProvenanceExplainNode node) {
    ProvenanceExplanationNodeResponse response = new ProvenanceExplanationNodeResponse();
    response.factId = node.fact().factId();
    response.factType = node.fact().factType();
    response.factKey = node.fact().factKey();
    response.producedByRule = node.fact().producedByRule();
    response.insertionType = node.fact().insertionType().name();
    response.inputFactIds = node.fact().inputFactIds();
    response.createdAt = node.fact().createdAt();
    response.retractedAt = node.fact().retractedAt();
    response.retractionReason = node.fact().retractionReason();
    response.dependsOn = node.dependsOn().stream().map(this::toExplanationNodeResponse).toList();
    return response;
  }
}
