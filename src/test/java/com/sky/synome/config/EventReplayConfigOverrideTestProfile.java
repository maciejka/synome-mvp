package com.sky.synome.config;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

public class EventReplayConfigOverrideTestProfile implements QuarkusTestProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.ofEntries(
        Map.entry("engine.event-replay-enabled", "false"),
        Map.entry("engine.event-replay-window", "45m"),
        Map.entry("engine.event-entrypoints", "transactions,audit"),
        Map.entry("engine.provenance-queue-capacity", "321"),
        Map.entry("engine.provenance-batch-size", "22"),
        Map.entry("engine.provenance-flush-interval", "900ms"),
        Map.entry("engine.provenance-retry-backoff", "120ms"),
        Map.entry("engine.security-enabled", "false"),
        Map.entry("engine.security-bootstrap-key", "override-key"),
        Map.entry("engine.security-bootstrap-name", "override-name"),
        Map.entry("engine.shutdown-timeout", "8s"),
        Map.entry("engine.ops-stream-poll-interval", "2s"),
        Map.entry("engine.ops-stream-max-batch", "12"));
  }
}
