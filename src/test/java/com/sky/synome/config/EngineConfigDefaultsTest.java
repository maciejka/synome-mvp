package com.sky.synome.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import org.junit.jupiter.api.Test;

@QuarkusTest
class EngineConfigDefaultsTest {

  @Inject EngineConfig engineConfig;

  @Test
  void eventReplayDefaultsAreMapped() {
    assertTrue(engineConfig.eventReplayEnabled());
    assertEquals(Duration.ofMinutes(30), engineConfig.eventReplayWindow());
    assertEquals("transactions", engineConfig.eventEntrypoints());
  }
}
