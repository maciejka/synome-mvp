package com.sky.synome.ops;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
public class EngineLifecycle {

  private static final int MAX_RECOVERY_EVENTS = 256;

  private final AtomicReference<State> state = new AtomicReference<>(State.STARTING);
  private final AtomicLong recoverySequence = new AtomicLong();
  private final List<RecoveryLifecycleEvent> recoveryEvents = new ArrayList<>();

  public void markRecovering(String phase, String message) {
    state.set(State.RECOVERING);
    addRecoveryEvent("RECOVERY", phase, message);
  }

  public void markRunning(String phase, String message) {
    state.set(State.RUNNING);
    addRecoveryEvent("RUNNING", phase, message);
  }

  public void markShutdown(String phase, String message) {
    state.set(State.SHUTTING_DOWN);
    addRecoveryEvent("SHUTDOWN", phase, message);
  }

  public boolean isReady() {
    return state.get() == State.RUNNING;
  }

  public boolean acceptsWrites() {
    return state.get() == State.RUNNING;
  }

  public State currentState() {
    return state.get();
  }

  public synchronized List<RecoveryLifecycleEvent> recoveryEventsAfter(long sequenceExclusive) {
    return recoveryEvents.stream().filter(e -> e.sequence() > sequenceExclusive).toList();
  }

  private synchronized void addRecoveryEvent(String eventType, String phase, String message) {
    long sequence = recoverySequence.incrementAndGet();
    recoveryEvents.add(
        new RecoveryLifecycleEvent(
            sequence, eventType, state.get().name(), phase, Instant.now(), message));
    while (recoveryEvents.size() > MAX_RECOVERY_EVENTS) {
      recoveryEvents.remove(0);
    }
  }

  public enum State {
    STARTING,
    RECOVERING,
    RUNNING,
    SHUTTING_DOWN
  }

  public record RecoveryLifecycleEvent(
      long sequence,
      String eventType,
      String lifecycleState,
      String phase,
      Instant timestamp,
      String message) {}
}
