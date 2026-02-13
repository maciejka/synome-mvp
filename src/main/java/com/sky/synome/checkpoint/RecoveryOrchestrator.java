package com.sky.synome.checkpoint;

import com.sky.synome.changeset.ChangesetLog;
import com.sky.synome.changeset.ChangesetReplayService;
import com.sky.synome.changeset.EventReplayService;
import com.sky.synome.config.EngineConfig;
import com.sky.synome.core.EngineSession;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import org.jboss.logging.Logger;

@ApplicationScoped
public class RecoveryOrchestrator {

  private static final Logger LOG = Logger.getLogger(RecoveryOrchestrator.class);

  @Inject EngineConfig engineConfig;

  @Inject CheckpointService checkpointService;

  @Inject ChangesetReplayService changesetReplayService;

  @Inject EventReplayService eventReplayService;

  @Inject ChangesetLog changesetLog;

  public RecoveryResult recover(EngineSession engineSession) {
    String checkpointIdForLogs = "none";
    long checkpointSequenceForLogs = 0L;
    int replayedChangesetsForLogs = 0;
    int replayedEventsForLogs = 0;
    int convergenceRulesForLogs = 0;
    String phase = "disabled";
    long start = System.currentTimeMillis();

    if (!engineConfig.recoveryEnabled()) {
      return new RecoveryResult(false, 0, 0, 0, 0);
    }

    var lock = engineSession.sessionLock();
    phase = "acquire_lock";
    if (!lock.tryAcquire(engineConfig.lockTimeoutMs())) {
      long durationMs = System.currentTimeMillis() - start;
      LOG.errorf(
          "recovery.lifecycle event=recovery.failure checkpointId=%s checkpointSequence=%d "
              + "replayedChangesets=%d replayedEvents=%d convergenceRules=%d "
              + "durationMs=%d phase=%s errorType=%s",
          checkpointIdForLogs,
          checkpointSequenceForLogs,
          replayedChangesetsForLogs,
          replayedEventsForLogs,
          convergenceRulesForLogs,
          durationMs,
          phase,
          "LockTimeout");
      throw new CheckpointException(
          "Could not acquire session lock for recovery bootstrap within "
              + engineConfig.lockTimeoutMs()
              + "ms",
          Map.of(
              "operation",
              "recovery_bootstrap",
              "phase",
              phase,
              "lockTimeoutMs",
              engineConfig.lockTimeoutMs()));
    }

    LOG.infof(
        "recovery.lifecycle event=recovery.start checkpointId=%s checkpointSequence=%d "
            + "replayedChangesets=%d replayedEvents=%d convergenceRules=%d durationMs=%d phase=%s",
        checkpointIdForLogs,
        checkpointSequenceForLogs,
        replayedChangesetsForLogs,
        replayedEventsForLogs,
        convergenceRulesForLogs,
        0,
        phase);

    CheckpointSnapshot snapshot = null;
    boolean snapshotRestored = false;
    try {
      phase = "cleanup_unfinalized";
      int removedReservations = changesetLog.deleteUnfinalizedReservations();
      if (removedReservations > 0) {
        LOG.infof(
            "recovery.lifecycle event=recovery.cleanup checkpointId=%s checkpointSequence=%d "
                + "replayedChangesets=%d replayedEvents=%d convergenceRules=%d "
                + "durationMs=%d phase=%s "
                + "removedReservations=%d",
            checkpointIdForLogs,
            checkpointSequenceForLogs,
            replayedChangesetsForLogs,
            replayedEventsForLogs,
            convergenceRulesForLogs,
            System.currentTimeMillis() - start,
            phase,
            removedReservations);
      }

      phase = "load_checkpoint";
      var latest = checkpointService.loadLatestSnapshot();
      if (latest.isEmpty()) {
        long durationMs = System.currentTimeMillis() - start;
        LOG.infof(
            "recovery.lifecycle event=recovery.skip_no_checkpoint "
                + "checkpointId=%s checkpointSequence=%d "
                + "replayedChangesets=%d replayedEvents=%d convergenceRules=%d "
                + "durationMs=%d phase=%s",
            checkpointIdForLogs,
            checkpointSequenceForLogs,
            replayedChangesetsForLogs,
            replayedEventsForLogs,
            convergenceRulesForLogs,
            durationMs,
            phase);
        return new RecoveryResult(false, 0, 0, 0, 0);
      }

      snapshot = latest.get();
      checkpointIdForLogs = snapshot.checkpointId().toString();
      checkpointSequenceForLogs = snapshot.sequenceNum();
      phase = "restore_snapshot";
      engineSession.restoreFromSnapshot(snapshot.facts(), snapshot.clockMillis(), false);
      snapshotRestored = true;

      phase = "replay_tail_facts";
      replayedChangesetsForLogs = changesetReplayService.replayAfter(snapshot.sequenceNum());
      phase = "replay_window_events";
      replayedEventsForLogs = eventReplayService.replayWindowEvents(snapshot.clockMillis());
      phase = "converge";
      convergenceRulesForLogs = engineSession.kieSession().fireAllRules();
      long durationMs = System.currentTimeMillis() - start;

      LOG.infof(
          "recovery.lifecycle event=recovery.success checkpointId=%s checkpointSequence=%d "
              + "replayedChangesets=%d replayedEvents=%d convergenceRules=%d "
              + "durationMs=%d phase=completed",
          checkpointIdForLogs,
          checkpointSequenceForLogs,
          replayedChangesetsForLogs,
          replayedEventsForLogs,
          convergenceRulesForLogs,
          durationMs);
      return new RecoveryResult(
          true,
          snapshot.sequenceNum(),
          replayedChangesetsForLogs,
          replayedEventsForLogs,
          convergenceRulesForLogs);
    } catch (RuntimeException e) {
      if (snapshotRestored && snapshot != null && shouldRestoreCheckpoint(phase)) {
        try {
          engineSession.restoreFromSnapshot(snapshot.facts(), snapshot.clockMillis(), false);
        } catch (RuntimeException rollbackFailure) {
          e.addSuppressed(rollbackFailure);
        }
      }

      long durationMs = System.currentTimeMillis() - start;
      LOG.errorf(
          e,
          "recovery.lifecycle event=recovery.failure checkpointId=%s checkpointSequence=%d "
              + "replayedChangesets=%d replayedEvents=%d convergenceRules=%d "
              + "durationMs=%d phase=%s errorType=%s",
          checkpointIdForLogs,
          checkpointSequenceForLogs,
          replayedChangesetsForLogs,
          replayedEventsForLogs,
          convergenceRulesForLogs,
          durationMs,
          phase,
          e.getClass().getSimpleName());
      throw new CheckpointException(
          "Recovery failed in phase="
              + phase
              + " checkpointId="
              + checkpointIdForLogs
              + " checkpointSequence="
              + checkpointSequenceForLogs
              + ": "
              + e.getMessage(),
          e,
          Map.of(
              "operation",
              "recovery_bootstrap",
              "phase",
              phase,
              "checkpointId",
              checkpointIdForLogs,
              "checkpointSequence",
              checkpointSequenceForLogs));
    } finally {
      lock.release();
    }
  }

  private boolean shouldRestoreCheckpoint(String phase) {
    return "replay_tail_facts".equals(phase)
        || "replay_window_events".equals(phase)
        || "converge".equals(phase);
  }

  public record RecoveryResult(
      boolean recoveredFromCheckpoint,
      long checkpointSequenceNum,
      int replayedChangesets,
      int replayedEvents,
      int convergenceRulesFired) {}
}
