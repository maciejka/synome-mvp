package com.sky.synome.changeset;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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
      changesetProcessor.replay(logged.changeset());
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
