package com.sky.synome.changeset;

import com.sky.synome.checkpoint.CheckpointException;
import com.sky.synome.config.EngineConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class EventReplayService {

  private static final Logger LOG = Logger.getLogger(EventReplayService.class);
  private static final int REPLAY_BATCH_SIZE = 500;

  @Inject EngineConfig engineConfig;

  @Inject ChangesetEventStore changesetEventStore;

  @Inject ChangesetProcessor changesetProcessor;

  public int replayWindowEvents(long checkpointClockMillis) {
    if (!engineConfig.eventReplayEnabled()) {
      return 0;
    }

    Duration window = engineConfig.eventReplayWindow();
    long windowMillis = Math.max(0L, window.toMillis());
    long replayWindowStartMillis = Math.max(0L, checkpointClockMillis - windowMillis);
    Instant replayWindowStart = Instant.ofEpochMilli(replayWindowStartMillis);
    Set<String> allowedEntrypoints = configuredEntrypoints();

    int replayed = 0;
    int offset = 0;
    while (true) {
      List<ChangesetEventStore.ProjectedEvent> batch =
          changesetEventStore.listReplayable(
              replayWindowStart, allowedEntrypoints, offset, REPLAY_BATCH_SIZE);
      if (batch.isEmpty()) {
        break;
      }

      for (ChangesetEventStore.ProjectedEvent event : batch) {
        replaySingleEvent(event, replayWindowStartMillis, checkpointClockMillis);
        replayed++;
      }
      offset += batch.size();
    }

    if (replayed > 0) {
      LOG.infof(
          "Replayed %d projected events with replayWindowStart=%s checkpointClockMillis=%d",
          replayed, replayWindowStart, checkpointClockMillis);
    }
    return replayed;
  }

  private void replaySingleEvent(
      ChangesetEventStore.ProjectedEvent event,
      long replayWindowStartMillis,
      long checkpointClockMillis) {
    try {
      changesetProcessor.replay(toReplayChangeset(event));
    } catch (RuntimeException e) {
      throw new CheckpointException(
          "Event replay failed for event_id="
              + event.eventId()
              + " sequence_num="
              + event.sequenceNum()
              + " replayWindowStartMillis="
              + replayWindowStartMillis
              + " checkpointClockMillis="
              + checkpointClockMillis,
          e,
          Map.of(
              "operation",
              "recovery_replay",
              "phase",
              "replay_events",
              "eventId",
              event.eventId(),
              "sequenceNum",
              event.sequenceNum(),
              "changesetId",
              event.changesetId(),
              "eventTimestamp",
              event.eventTimestamp().toString()));
    }
  }

  private Changeset toReplayChangeset(ChangesetEventStore.ProjectedEvent event) {
    ChangesetEntry entry =
        new ChangesetEntry(
            EntryKind.EVENT,
            ChangesetAction.EMIT,
            null,
            event.factType(),
            event.payload(),
            event.entryPoint(),
            event.eventTimestamp());
    UUID replayId =
        UUID.nameUUIDFromBytes(
            (event.changesetId().toString() + ":" + event.eventId())
                .getBytes(StandardCharsets.UTF_8));
    return new Changeset(
        replayId,
        List.of(entry),
        Map.of(
            "source", "recovery-event-replay",
            "changesetId", event.changesetId().toString(),
            "eventId", event.eventId()));
  }

  private Set<String> configuredEntrypoints() {
    String configured = engineConfig.eventEntrypoints();
    if (configured == null || configured.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(configured.split(","))
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .collect(java.util.stream.Collectors.toSet());
  }
}
