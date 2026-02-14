package com.sky.synome.config;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

public class EventReplayConfigOverrideTestProfile implements QuarkusTestProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of(
        "engine.event-replay-enabled", "false",
        "engine.event-replay-window", "45m",
        "engine.event-entrypoints", "transactions,audit",
        "engine.provenance-queue-capacity", "321",
        "engine.provenance-batch-size", "22",
        "engine.provenance-flush-interval", "900ms",
        "engine.provenance-retry-backoff", "120ms");
  }
}
