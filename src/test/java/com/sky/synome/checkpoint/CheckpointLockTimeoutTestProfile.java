package com.sky.synome.checkpoint;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

public class CheckpointLockTimeoutTestProfile implements QuarkusTestProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of("engine.lock-timeout-ms", "100");
  }
}
