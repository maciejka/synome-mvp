package com.sky.synome.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Duration;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(EventReplayConfigOverrideTestProfile.class)
class EngineConfigOverridesTest {

  @Inject EngineConfig engineConfig;

  @Test
  void eventReplayOverridesAreMapped() {
    assertFalse(engineConfig.eventReplayEnabled());
    assertEquals(Duration.ofMinutes(45), engineConfig.eventReplayWindow());
    assertEquals("transactions,audit", engineConfig.eventEntrypoints());
    assertEquals(321, engineConfig.provenanceQueueCapacity());
    assertEquals(22, engineConfig.provenanceBatchSize());
    assertEquals(Duration.ofMillis(900), engineConfig.provenanceFlushInterval());
    assertEquals(Duration.ofMillis(120), engineConfig.provenanceRetryBackoff());
  }
}
