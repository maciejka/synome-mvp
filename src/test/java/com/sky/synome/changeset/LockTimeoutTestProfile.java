package com.sky.synome.changeset;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

public class LockTimeoutTestProfile implements QuarkusTestProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of("engine.lock-timeout-ms", "50");
  }
}
