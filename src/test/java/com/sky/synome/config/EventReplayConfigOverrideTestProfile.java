package com.sky.synome.config;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

public class EventReplayConfigOverrideTestProfile implements QuarkusTestProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of(
        "engine.event-replay-enabled", "false",
        "engine.event-replay-window", "45m",
        "engine.event-entrypoints", "transactions,audit");
  }
}
