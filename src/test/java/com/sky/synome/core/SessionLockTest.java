package com.sky.synome.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SessionLockTest {

  @Test
  void lockCanBeAcquiredAndReleasedByOwner() {
    SessionLock lock = new SessionLock();

    assertTrue(lock.tryAcquire(100));
    lock.release();
    assertTrue(lock.tryAcquire(100));
    lock.release();
  }

  @Test
  void releaseFromDifferentThreadIsIgnored() throws Exception {
    SessionLock lock = new SessionLock();
    assertTrue(lock.tryAcquire(100));

    Thread nonOwner = new Thread(lock::release);
    nonOwner.start();
    nonOwner.join();

    lock.release();
    assertTrue(lock.tryAcquire(100));
    lock.release();
  }

  @Test
  void interruptedAcquireReturnsFalseAndPreservesInterruptStatus() {
    SessionLock lock = new SessionLock();
    Thread.currentThread().interrupt();
    try {
      assertFalse(lock.tryAcquire(100));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }
}
