package com.sky.synome.security;

import com.sky.synome.api.dto.ErrorResponse;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

@ApplicationScoped
@Unremovable
@Provider
public class ApiSecurityFilters {

  private static final String API_PREFIX = "/api/v1/";
  private static final String API_KEY_HEADER = "X-Api-Key";

  @jakarta.inject.Inject ApiKeyService apiKeyService;

  @ServerRequestFilter(priority = Priorities.AUTHENTICATION)
  public Response authenticate(ContainerRequestContext requestContext) {
    if (!isBusinessApiRequest(requestContext)) {
      return null;
    }

    String apiKeyHeader = requestContext.getHeaderString(API_KEY_HEADER);
    ApiKeyService.AuthenticationResult result = apiKeyService.authenticate(apiKeyHeader);
    if (!result.authenticated()) {
      return buildUnauthorizedResponse(result.failureReason());
    }

    requestContext.setProperty(
        SecurityRequestContext.AUTHENTICATED_API_KEY, result.authenticatedApiKey());
    return null;
  }

  @ServerRequestFilter(priority = Priorities.AUTHORIZATION)
  public Response authorize(ContainerRequestContext requestContext, ResourceInfo resourceInfo) {
    if (!isBusinessApiRequest(requestContext)) {
      return null;
    }

    AuthenticatedApiKey authenticatedApiKey =
        SecurityRequestContext.authenticatedApiKey(requestContext);
    if (authenticatedApiKey == null) {
      return buildUnauthorizedResponse(ApiKeyService.AuthenticationFailureReason.MISSING_KEY);
    }

    RequiresPermission requiresPermission = resolveRequiredPermission(resourceInfo);
    if (requiresPermission == null) {
      return null;
    }

    ApiPermission requiredPermission = requiresPermission.value();
    if (!authenticatedApiKey.hasPermission(requiredPermission)) {
      return buildForbiddenResponse(requiredPermission);
    }

    return null;
  }

  private boolean isBusinessApiRequest(ContainerRequestContext requestContext) {
    String path = requestContext.getUriInfo().getPath();
    return path != null
        && (path.startsWith(API_PREFIX) || path.startsWith(API_PREFIX.substring(1)));
  }

  private RequiresPermission resolveRequiredPermission(ResourceInfo resourceInfo) {
    if (resourceInfo == null) {
      return null;
    }
    if (resourceInfo.getResourceMethod() != null) {
      RequiresPermission methodPermission =
          resourceInfo.getResourceMethod().getAnnotation(RequiresPermission.class);
      if (methodPermission != null) {
        return methodPermission;
      }
    }
    if (resourceInfo.getResourceClass() != null) {
      return resourceInfo.getResourceClass().getAnnotation(RequiresPermission.class);
    }
    return null;
  }

  private Response buildUnauthorizedResponse(ApiKeyService.AuthenticationFailureReason reason) {
    String requestId = UUID.randomUUID().toString();
    String code;
    String message;

    if (reason == ApiKeyService.AuthenticationFailureReason.MISSING_KEY) {
      code = "API_KEY_REQUIRED";
      message = "Missing X-Api-Key header";
    } else if (reason == ApiKeyService.AuthenticationFailureReason.INACTIVE_KEY) {
      code = "API_KEY_INACTIVE";
      message = "API key is inactive";
    } else if (reason == ApiKeyService.AuthenticationFailureReason.EXPIRED_KEY) {
      code = "API_KEY_EXPIRED";
      message = "API key is expired";
    } else {
      code = "API_KEY_INVALID";
      message = "Invalid API key";
    }

    ErrorResponse error =
        new ErrorResponse(
            code, message, Map.of("requiredHeader", API_KEY_HEADER), Instant.now(), requestId);

    return Response.status(Response.Status.UNAUTHORIZED)
        .type(MediaType.APPLICATION_JSON)
        .entity(error)
        .build();
  }

  private Response buildForbiddenResponse(ApiPermission requiredPermission) {
    ErrorResponse error =
        new ErrorResponse(
            "PERMISSION_DENIED",
            "API key does not have required permission",
            Map.of("requiredPermission", requiredPermission.name()),
            Instant.now(),
            UUID.randomUUID().toString());
    return Response.status(Response.Status.FORBIDDEN)
        .type(MediaType.APPLICATION_JSON)
        .entity(error)
        .build();
  }
}
