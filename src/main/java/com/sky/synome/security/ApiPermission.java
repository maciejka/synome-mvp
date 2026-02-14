package com.sky.synome.security;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public enum ApiPermission {
  CHANGESET_WRITE,
  FACT_READ,
  CHECKPOINT_ADMIN,
  RULE_ADMIN,
  PROVENANCE_READ,
  OPS_STREAM_READ;

  public static Set<ApiPermission> parseArray(String[] rawPermissions) {
    if (rawPermissions == null || rawPermissions.length == 0) {
      return Set.of();
    }

    EnumSet<ApiPermission> permissions = EnumSet.noneOf(ApiPermission.class);
    for (String rawPermission : rawPermissions) {
      if (rawPermission == null || rawPermission.isBlank()) {
        continue;
      }
      permissions.add(ApiPermission.valueOf(rawPermission.trim().toUpperCase(Locale.ROOT)));
    }
    return Collections.unmodifiableSet(permissions);
  }

  public static Set<ApiPermission> parseCsv(String rawPermissions) {
    if (rawPermissions == null || rawPermissions.isBlank()) {
      return Set.of();
    }
    return parseArray(rawPermissions.split(","));
  }

  public static String[] toArray(Set<ApiPermission> permissions) {
    if (permissions == null || permissions.isEmpty()) {
      return new String[0];
    }
    return permissions.stream().map(ApiPermission::name).sorted().toArray(String[]::new);
  }
}
