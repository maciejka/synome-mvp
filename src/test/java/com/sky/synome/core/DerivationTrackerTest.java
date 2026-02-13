package com.sky.synome.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

class DerivationTrackerTest {

  @Test
  void objectUpdatedIsNoOp() {
    DerivationTracker tracker = new DerivationTracker();
    assertDoesNotThrow(() -> tracker.objectUpdated(null));
  }
}
