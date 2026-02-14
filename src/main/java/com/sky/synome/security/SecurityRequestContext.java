package com.sky.synome.security;

import jakarta.ws.rs.container.ContainerRequestContext;

public final class SecurityRequestContext {

  public static final String AUTHENTICATED_API_KEY =
      SecurityRequestContext.class.getName() + ".authenticatedApiKey";

  private SecurityRequestContext() {}

  public static AuthenticatedApiKey authenticatedApiKey(ContainerRequestContext requestContext) {
    Object value = requestContext.getProperty(AUTHENTICATED_API_KEY);
    if (value instanceof AuthenticatedApiKey authenticatedApiKey) {
      return authenticatedApiKey;
    }
    return null;
  }
}
