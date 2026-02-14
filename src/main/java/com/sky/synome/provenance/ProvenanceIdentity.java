package com.sky.synome.provenance;

import java.util.UUID;

public final class ProvenanceIdentity {

  private static final String BASE_PREFIX = "base:";
  private static final String EVENT_PREFIX = "event:";
  private static final String DERIVED_PREFIX = "derived:";

  private ProvenanceIdentity() {}

  public static String baseFactId(String factKey) {
    return BASE_PREFIX + factKey;
  }

  public static String eventFactId() {
    return EVENT_PREFIX + UUID.randomUUID();
  }

  public static String derivedFactId() {
    return DERIVED_PREFIX + UUID.randomUUID();
  }

  public static boolean isDerived(String factId) {
    return factId != null && factId.startsWith(DERIVED_PREFIX);
  }
}
