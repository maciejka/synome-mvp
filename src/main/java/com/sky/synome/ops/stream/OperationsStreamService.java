package com.sky.synome.ops.stream;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import com.sky.synome.config.EngineConfig;
import com.sky.synome.ops.EngineLifecycle;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.Table;

@ApplicationScoped
public class OperationsStreamService {

  private static final Table<Record> CHANGESET_LOG_TABLE = table("changeset_log");
  private static final Table<Record> CHECKPOINTS_TABLE = table("checkpoints");

  private static final Field<Long> CHANGESET_SEQUENCE_NUM_FIELD = field("sequence_num", Long.class);
  private static final Field<UUID> CHANGESET_ID_FIELD = field("changeset_id", UUID.class);
  private static final Field<OffsetDateTime> APPLIED_AT_FIELD =
      field("applied_at", OffsetDateTime.class);
  private static final Field<Integer> RULES_FIRED_FIELD = field("rules_fired", Integer.class);
  private static final Field<Integer> DURATION_MS_FIELD = field("duration_ms", Integer.class);
  private static final Field<JSONB> CHANGESET_RESPONSE_PAYLOAD_FIELD =
      field("response_payload", JSONB.class);
  private static final Field<String> CHANGESET_ERROR_FIELD = field("error", String.class);

  private static final Field<UUID> CHECKPOINT_ID_FIELD = field("checkpoint_id", UUID.class);
  private static final Field<Long> CHECKPOINT_SEQUENCE_NUM_FIELD =
      field("sequence_num", Long.class);
  private static final Field<OffsetDateTime> CHECKPOINT_CREATED_AT_FIELD =
      field("created_at", OffsetDateTime.class);
  private static final Field<Integer> CHECKPOINT_FACT_COUNT_FIELD =
      field("fact_count", Integer.class);
  private static final Field<Long> CHECKPOINT_SIZE_BYTES_FIELD = field("size_bytes", Long.class);

  @Inject DSLContext dsl;

  @Inject EngineConfig engineConfig;

  @Inject EngineLifecycle engineLifecycle;

  public Multi<ChangesetStreamEvent> changesets(int maxEvents) {
    AtomicLong lastSequence = new AtomicLong(latestChangesetSequence());
    Multi<ChangesetStreamEvent> source =
        Multi.createBy()
            .concatenating()
            .streams(
                Multi.createFrom().item(openChangesetEvent(lastSequence.get())),
                poll()
                    .onItem()
                    .transformToMultiAndConcatenate(
                        ignored ->
                            Multi.createFrom().iterable(fetchChangesetsAfter(lastSequence))));
    return applyMaxEvents(source, maxEvents);
  }

  public Multi<CheckpointStreamEvent> checkpoints(int maxEvents) {
    AtomicLong lastSequence = new AtomicLong(latestCheckpointSequence());
    Multi<CheckpointStreamEvent> source =
        Multi.createBy()
            .concatenating()
            .streams(
                Multi.createFrom().item(openCheckpointEvent(lastSequence.get())),
                poll()
                    .onItem()
                    .transformToMultiAndConcatenate(
                        ignored ->
                            Multi.createFrom().iterable(fetchCheckpointsAfter(lastSequence))));
    return applyMaxEvents(source, maxEvents);
  }

  public Multi<RecoveryStreamEvent> recovery(int maxEvents) {
    AtomicLong lastSequence = new AtomicLong(0L);
    Multi<RecoveryStreamEvent> source =
        Multi.createBy()
            .concatenating()
            .streams(
                Multi.createFrom().item(openRecoveryEvent()),
                poll()
                    .onItem()
                    .transformToMultiAndConcatenate(
                        ignored ->
                            Multi.createFrom().iterable(fetchRecoveryEventsAfter(lastSequence))));
    return applyMaxEvents(source, maxEvents);
  }

  private Multi<Long> poll() {
    Duration interval = engineConfig.opsStreamPollInterval();
    if (interval == null || interval.isNegative() || interval.isZero()) {
      interval = Duration.ofSeconds(1);
    }
    return Multi.createFrom().ticks().every(interval);
  }

  private List<ChangesetStreamEvent> fetchChangesetsAfter(AtomicLong lastSequence) {
    List<ChangesetStreamEvent> events =
        dsl.select(
                CHANGESET_SEQUENCE_NUM_FIELD,
                CHANGESET_ID_FIELD,
                APPLIED_AT_FIELD,
                RULES_FIRED_FIELD,
                DURATION_MS_FIELD)
            .from(CHANGESET_LOG_TABLE)
            .where(CHANGESET_SEQUENCE_NUM_FIELD.gt(lastSequence.get()))
            .and(CHANGESET_RESPONSE_PAYLOAD_FIELD.isNotNull())
            .and(CHANGESET_ERROR_FIELD.isNull())
            .orderBy(CHANGESET_SEQUENCE_NUM_FIELD.asc())
            .limit(engineConfig.opsStreamMaxBatch())
            .fetch(
                record ->
                    new ChangesetStreamEvent(
                        "CHANGESET_APPLIED",
                        record.get(CHANGESET_SEQUENCE_NUM_FIELD),
                        record.get(CHANGESET_ID_FIELD),
                        toInstant(record.get(APPLIED_AT_FIELD)),
                        record.get(RULES_FIRED_FIELD),
                        record.get(DURATION_MS_FIELD)));

    if (!events.isEmpty()) {
      lastSequence.set(events.get(events.size() - 1).sequenceNum());
    }
    return events;
  }

  private List<CheckpointStreamEvent> fetchCheckpointsAfter(AtomicLong lastSequence) {
    List<CheckpointStreamEvent> events =
        dsl.select(
                CHECKPOINT_ID_FIELD,
                CHECKPOINT_SEQUENCE_NUM_FIELD,
                CHECKPOINT_CREATED_AT_FIELD,
                CHECKPOINT_FACT_COUNT_FIELD,
                CHECKPOINT_SIZE_BYTES_FIELD)
            .from(CHECKPOINTS_TABLE)
            .where(CHECKPOINT_SEQUENCE_NUM_FIELD.gt(lastSequence.get()))
            .orderBy(CHECKPOINT_SEQUENCE_NUM_FIELD.asc())
            .limit(engineConfig.opsStreamMaxBatch())
            .fetch(
                record ->
                    new CheckpointStreamEvent(
                        "CHECKPOINT_CREATED",
                        record.get(CHECKPOINT_ID_FIELD),
                        record.get(CHECKPOINT_SEQUENCE_NUM_FIELD),
                        toInstant(record.get(CHECKPOINT_CREATED_AT_FIELD)),
                        record.get(CHECKPOINT_FACT_COUNT_FIELD),
                        record.get(CHECKPOINT_SIZE_BYTES_FIELD)));

    if (!events.isEmpty()) {
      lastSequence.set(events.get(events.size() - 1).sequenceNum());
    }
    return events;
  }

  private List<RecoveryStreamEvent> fetchRecoveryEventsAfter(AtomicLong lastSequence) {
    List<RecoveryStreamEvent> events = new ArrayList<>();
    for (EngineLifecycle.RecoveryLifecycleEvent event :
        engineLifecycle.recoveryEventsAfter(lastSequence.get())) {
      events.add(
          new RecoveryStreamEvent(
              event.eventType(),
              event.sequence(),
              event.lifecycleState(),
              event.phase(),
              event.timestamp(),
              event.message()));
    }

    if (!events.isEmpty()) {
      lastSequence.set(events.get(events.size() - 1).sequence());
    }
    return events;
  }

  private long latestChangesetSequence() {
    Long sequence =
        dsl.select(CHANGESET_SEQUENCE_NUM_FIELD)
            .from(CHANGESET_LOG_TABLE)
            .where(CHANGESET_RESPONSE_PAYLOAD_FIELD.isNotNull())
            .and(CHANGESET_ERROR_FIELD.isNull())
            .orderBy(CHANGESET_SEQUENCE_NUM_FIELD.desc())
            .limit(1)
            .fetchOne(CHANGESET_SEQUENCE_NUM_FIELD);
    return sequence == null ? 0L : sequence.longValue();
  }

  private long latestCheckpointSequence() {
    Long sequence =
        dsl.select(CHECKPOINT_SEQUENCE_NUM_FIELD)
            .from(CHECKPOINTS_TABLE)
            .orderBy(CHECKPOINT_SEQUENCE_NUM_FIELD.desc())
            .limit(1)
            .fetchOne(CHECKPOINT_SEQUENCE_NUM_FIELD);
    return sequence == null ? 0L : sequence.longValue();
  }

  private ChangesetStreamEvent openChangesetEvent(long lastSequence) {
    return new ChangesetStreamEvent("STREAM_OPENED", lastSequence, null, null, null, null);
  }

  private CheckpointStreamEvent openCheckpointEvent(long lastSequence) {
    return new CheckpointStreamEvent("STREAM_OPENED", null, lastSequence, null, null, null);
  }

  private RecoveryStreamEvent openRecoveryEvent() {
    return new RecoveryStreamEvent(
        "STREAM_OPENED", 0L, engineLifecycle.currentState().name(), "open", null, "stream opened");
  }

  private <T> Multi<T> applyMaxEvents(Multi<T> source, int maxEvents) {
    if (maxEvents <= 0) {
      return source;
    }
    return source.select().first(maxEvents);
  }

  private java.time.Instant toInstant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
