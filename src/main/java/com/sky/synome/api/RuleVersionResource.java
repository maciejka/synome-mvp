package com.sky.synome.api;

import com.sky.synome.api.dto.RuleActivationResponse;
import com.sky.synome.api.dto.RuleUploadResponse;
import com.sky.synome.api.dto.RuleValidationRequest;
import com.sky.synome.api.dto.RuleValidationResponse;
import com.sky.synome.api.dto.RuleVersionSummaryResponse;
import com.sky.synome.rules.RuleVersionService;
import com.sky.synome.rules.RuleVersionService.ActivationResult;
import com.sky.synome.rules.RuleVersionService.RuleSubmission;
import com.sky.synome.rules.RuleVersionService.UploadResult;
import com.sky.synome.rules.RuleVersionStore.RuleVersion;
import com.sky.synome.security.ApiPermission;
import com.sky.synome.security.RequiresPermission;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
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

@Path("/api/v1/rules")
@Produces(MediaType.APPLICATION_JSON)
@RequiresPermission(ApiPermission.RULE_ADMIN)
public class RuleVersionResource {

  @Inject RuleVersionService ruleVersionService;

  @POST
  @Path("/validate")
  @Consumes(MediaType.APPLICATION_JSON)
  public RuleValidationResponse validate(RuleValidationRequest request) {
    return toValidationResponse(ruleVersionService.validate(toSubmission(request)));
  }

  @POST
  @Path("/upload")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response upload(RuleValidationRequest request) {
    UploadResult result = ruleVersionService.upload(toSubmission(request));
    RuleUploadResponse response = new RuleUploadResponse();
    fillSummary(response, result.version());
    response.validation = toValidationResponse(result.validationReport());
    return Response.status(Response.Status.CREATED).entity(response).build();
  }

  @POST
  @Path("/{versionId}/activate")
  public RuleActivationResponse activate(@PathParam("versionId") UUID versionId) {
    return toActivationResponse("ACTIVATED", ruleVersionService.activate(versionId));
  }

  @POST
  @Path("/{versionId}/rollback")
  public RuleActivationResponse rollback(@PathParam("versionId") UUID versionId) {
    return toActivationResponse("ROLLED_BACK", ruleVersionService.rollback(versionId));
  }

  @GET
  public List<RuleVersionSummaryResponse> list(
      @QueryParam("limit") @DefaultValue("20") int limit,
      @QueryParam("offset") @DefaultValue("0") int offset) {
    return ruleVersionService.list(limit, offset).stream().map(this::toSummary).toList();
  }

  @GET
  @Path("/active")
  public RuleVersionSummaryResponse active() {
    return toSummary(ruleVersionService.activeVersion());
  }

  private RuleSubmission toSubmission(RuleValidationRequest request) {
    if (request == null) {
      return null;
    }
    return new RuleSubmission(
        request.versionLabel,
        request.drlFiles,
        request.drl,
        request.sourcePath,
        request.uploadedBy);
  }

  private RuleActivationResponse toActivationResponse(String status, ActivationResult result) {
    RuleActivationResponse response = new RuleActivationResponse();
    response.status = status;
    response.activatedVersionId = result.activeVersion().versionId();
    response.previousVersionId = result.previousVersionId();
    response.checkpointId = result.checkpointId();
    response.replayedEvents = result.replayedEvents();
    response.convergenceRulesFired = result.convergenceRulesFired();
    response.activatedAt = result.activatedAt();
    return response;
  }

  private RuleVersionSummaryResponse toSummary(RuleVersion version) {
    RuleVersionSummaryResponse response = new RuleVersionSummaryResponse();
    fillSummary(response, version);
    return response;
  }

  private void fillSummary(RuleVersionSummaryResponse response, RuleVersion version) {
    response.versionId = version.versionId();
    response.versionLabel = version.versionLabel();
    response.checksum = version.checksum();
    response.uploadedBy = version.uploadedBy();
    response.uploadedAt = version.uploadedAt();
    response.activatedAt = version.activatedAt();
    response.deactivatedAt = version.deactivatedAt();
    response.active = version.isActive();
  }

  private RuleValidationResponse toValidationResponse(RuleVersionService.ValidationReport report) {
    RuleValidationResponse response = new RuleValidationResponse();
    response.valid = report.valid();
    response.compiled = report.compiled();
    response.compatible = report.compatible();
    response.checksum = report.checksum();
    response.compilationErrors = report.compilationErrors();
    response.compatibilityErrors = report.compatibilityErrors();
    return response;
  }
}
