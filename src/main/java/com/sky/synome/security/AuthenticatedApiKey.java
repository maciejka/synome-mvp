package com.sky.synome.security;

import java.util.Set;
import java.util.UUID;

public record AuthenticatedApiKey(UUID keyId, String name, Set<ApiPermission> permissions) {

  public boolean hasPermission(ApiPermission permission) {
    return permissions != null && permissions.contains(permission);
  }
}
