package com.sky.synome.changeset;

import com.sky.synome.checkpoint.CheckpointException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
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
      Changeset factOnlyChangeset = toFactOnlyChangeset(logged.changeset());
      if (factOnlyChangeset.entries().isEmpty()) {
        continue;
      }
      try {
        changesetProcessor.replay(factOnlyChangeset);
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
          "Replayed %d finalized FACT changesets after sequence %d",
          replayed, sequenceBoundaryExclusive);
    }
    return replayed;
  }

  private Changeset toFactOnlyChangeset(Changeset changeset) {
    if (changeset.entries() == null) {
      throw new IllegalStateException("Replay payload is missing entries");
    }
    List<ChangesetEntry> factEntries =
        changeset.entries().stream().filter(entry -> entry.kind() == EntryKind.FACT).toList();
    return new Changeset(changeset.id(), factEntries, changeset.metadata());
  }
}
