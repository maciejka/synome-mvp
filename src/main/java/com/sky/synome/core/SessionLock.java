package com.sky.synome.core;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class SessionLock {

  private final ReentrantLock lock = new ReentrantLock();

  public boolean tryAcquire(long timeoutMs) {
    try {
      return lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  public void release() {
    if (lock.isHeldByCurrentThread()) {
      lock.unlock();
    }
  }
}
