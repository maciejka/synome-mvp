package com.sky.synome.checkpoint;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

public class RecoveryEnabledTestProfile implements QuarkusTestProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of("engine.recovery-enabled", "true");
  }
}
