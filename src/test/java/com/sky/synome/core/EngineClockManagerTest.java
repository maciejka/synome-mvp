package com.sky.synome.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class EngineClockManagerTest {

  @Inject EngineClockManager engineClockManager;

  @Inject EngineSession engineSession;

  @BeforeEach
  void resetSession() {
    engineSession.restoreFromSnapshot(List.of(), 0L, true);
  }

  @Test
  void liveModeNeverRewindsClock() {
    engineClockManager.advanceToEventTime(
        engineSession.kieSession(),
        Instant.ofEpochMilli(2_000L),
        EngineClockManager.ClockMode.LIVE);
    engineClockManager.advanceToEventTime(
        engineSession.kieSession(),
        Instant.ofEpochMilli(1_000L),
        EngineClockManager.ClockMode.LIVE);

    assertEquals(2_000L, engineClockManager.currentTimeMillis(engineSession.kieSession()));
  }

  @Test
  void replayModeAdvancesDeterministicallyInOrder() {
    engineClockManager.advanceToEventTime(
        engineSession.kieSession(),
        Instant.ofEpochMilli(1_000L),
        EngineClockManager.ClockMode.REPLAY);
    engineClockManager.advanceToEventTime(
        engineSession.kieSession(),
        Instant.ofEpochMilli(3_000L),
        EngineClockManager.ClockMode.REPLAY);
    engineClockManager.advanceToEventTime(
        engineSession.kieSession(),
        Instant.ofEpochMilli(500L),
        EngineClockManager.ClockMode.REPLAY);

    assertEquals(3_000L, engineClockManager.currentTimeMillis(engineSession.kieSession()));
  }
}
