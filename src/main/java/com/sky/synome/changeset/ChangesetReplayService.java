package com.sky.synome.changeset;

import com.sky.synome.checkpoint.CheckpointException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ChangesetReplayService {

  private static final Logger LOG = Logger.getLogger(ChangesetReplayService.class);

  @Inject ChangesetLog changesetLog;

  @Inject ChangesetProcessor changesetProcessor;

  public int replayAfter(long sequenceBoundaryExclusive) {
    int replayed = 0;
    for (ChangesetLog.ReplayableLoggedChangeset logged :
        changesetLog.listFinalizedAfter(sequenceBoundaryExclusive)) {
      try {
        changesetProcessor.replay(logged.changeset());
      } catch (RuntimeException e) {
        throw new CheckpointException(
            "Replay failed for sequence_num="
                + logged.sequenceNum()
                + " after checkpoint sequence="
                + sequenceBoundaryExclusive,
            e,
            Map.of(
                "operation",
                "recovery_replay",
                "phase",
                "replay_tail",
                "sequenceNum",
                logged.sequenceNum(),
                "checkpointSequence",
                sequenceBoundaryExclusive));
      }
      replayed++;
    }
    if (replayed > 0) {
      LOG.infof(
          "Replayed %d finalized changesets after sequence %d",
          replayed, sequenceBoundaryExclusive);
    }
    return replayed;
  }
}
