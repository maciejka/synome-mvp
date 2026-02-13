package com.sky.synome.core;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;
import org.kie.api.runtime.KieSession;
import org.kie.api.time.SessionPseudoClock;

@ApplicationScoped
public class EngineClockManager {

  private static final Logger LOG = Logger.getLogger(EngineClockManager.class);

  public enum ClockMode {
    LIVE,
    REPLAY
  }

  public long currentTimeMillis(KieSession session) {
    return pseudoClock(session).getCurrentTime();
  }

  public void advanceToEventTime(KieSession session, Instant eventTimestamp, ClockMode mode) {
    SessionPseudoClock clock = pseudoClock(session);
    long targetMillis = eventTimestamp.toEpochMilli();
    long currentMillis = clock.getCurrentTime();

    if (targetMillis > currentMillis) {
      clock.advanceTime(targetMillis - currentMillis, TimeUnit.MILLISECONDS);
      return;
    }

    if (targetMillis < currentMillis) {
      LOG.debugf(
          "Ignoring backward clock move in %s mode: target=%d current=%d",
          mode, targetMillis, currentMillis);
    }
  }

  private SessionPseudoClock pseudoClock(KieSession session) {
    if (!(session.getSessionClock() instanceof SessionPseudoClock clock)) {
      throw new IllegalStateException("KieSession is not configured with a pseudo clock");
    }
    return clock;
  }
}
