package com.sky.synome.checkpoint;

import com.sky.synome.changeset.ChangesetLog;
import com.sky.synome.changeset.ChangesetReplayService;
import com.sky.synome.config.EngineConfig;
import com.sky.synome.core.EngineSession;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class RecoveryOrchestrator {

  private static final Logger LOG = Logger.getLogger(RecoveryOrchestrator.class);

  @Inject EngineConfig engineConfig;

  @Inject CheckpointService checkpointService;

  @Inject ChangesetReplayService changesetReplayService;

  @Inject ChangesetLog changesetLog;

  public RecoveryResult recover(EngineSession engineSession) {
    if (!engineConfig.recoveryEnabled()) {
      return new RecoveryResult(false, 0, 0, 0);
    }

    var lock = engineSession.sessionLock();
    if (!lock.tryAcquire(engineConfig.lockTimeoutMs())) {
      throw new CheckpointException("Could not acquire session lock for recovery bootstrap");
    }

    long start = System.currentTimeMillis();
    try {
      int removedReservations = changesetLog.deleteUnfinalizedReservations();
      if (removedReservations > 0) {
        LOG.infof(
            "Removed %d unfinalized changeset reservations before recovery", removedReservations);
      }

      var latest = checkpointService.loadLatestSnapshot();
      if (latest.isEmpty()) {
        LOG.info("No checkpoint found; starting with fresh session");
        return new RecoveryResult(false, 0, 0, 0);
      }

      CheckpointSnapshot snapshot = latest.get();
      engineSession.restoreFromSnapshot(snapshot.facts(), snapshot.clockMillis(), false);
      int replayed = changesetReplayService.replayAfter(snapshot.sequenceNum());
      int convergenceRules = engineSession.kieSession().fireAllRules();
      long durationMs = System.currentTimeMillis() - start;

      LOG.infof(
          "Recovery complete: checkpoint=%s sequence=%d facts=%d replayed=%d "
              + "convergenceRules=%d durationMs=%d",
          snapshot.checkpointId(),
          snapshot.sequenceNum(),
          snapshot.facts().size(),
          replayed,
          convergenceRules,
          durationMs);
      return new RecoveryResult(true, snapshot.sequenceNum(), replayed, convergenceRules);
    } finally {
      lock.release();
    }
  }

  public record RecoveryResult(
      boolean recoveredFromCheckpoint,
      long checkpointSequenceNum,
      int replayedChangesets,
      int convergenceRulesFired) {}
}
