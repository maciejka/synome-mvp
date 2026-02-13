package com.sky.synome.changeset;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.table;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.Table;

@ApplicationScoped
public class ChangesetEventStore {

  private static final Table<Record> CHANGESET_EVENTS_TABLE = table("changeset_events");
  private static final Field<Long> EVENT_ID_FIELD = field("event_id", Long.class);
  private static final Field<UUID> CHANGESET_ID_FIELD = field("changeset_id", UUID.class);
  private static final Field<Long> SEQUENCE_NUM_FIELD = field("sequence_num", Long.class);
  private static final Field<String> ENTRY_POINT_FIELD = field("entry_point", String.class);
  private static final Field<String> FACT_TYPE_FIELD = field("fact_type", String.class);
  private static final Field<OffsetDateTime> EVENT_TIMESTAMP_FIELD =
      field("event_timestamp", OffsetDateTime.class);
  private static final Field<JSONB> PAYLOAD_FIELD = field("payload", JSONB.class);

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  @Inject DSLContext dsl;

  @Inject ObjectMapper objectMapper;

  public void persistProjectedEvents(
      UUID changesetId, long sequenceNum, List<ChangesetEntry> entries) {
    for (ChangesetEntry entry : entries) {
      if (entry.kind() != EntryKind.EVENT || entry.action() != ChangesetAction.EMIT) {
        continue;
      }
      dsl.insertInto(CHANGESET_EVENTS_TABLE)
          .set(CHANGESET_ID_FIELD, changesetId)
          .set(SEQUENCE_NUM_FIELD, sequenceNum)
          .set(ENTRY_POINT_FIELD, entry.entryPoint())
          .set(FACT_TYPE_FIELD, entry.factType())
          .set(EVENT_TIMESTAMP_FIELD, toOffsetDateTime(entry.timestamp()))
          .set(PAYLOAD_FIELD, JSONB.valueOf(serializePayload(entry.data())))
          .execute();
    }
  }

  public int deleteBySequence(long sequenceNum) {
    return dsl.deleteFrom(CHANGESET_EVENTS_TABLE)
        .where(SEQUENCE_NUM_FIELD.eq(sequenceNum))
        .execute();
  }

  public List<ProjectedEvent> listReplayable(
      Instant fromInclusive, Set<String> allowedEntrypoints, long offset, int limit) {
    Condition condition = EVENT_TIMESTAMP_FIELD.ge(toOffsetDateTime(fromInclusive));
    if (!allowedEntrypoints.isEmpty()) {
      condition = condition.and(ENTRY_POINT_FIELD.in(allowedEntrypoints));
    }
    return dsl.select(
            EVENT_ID_FIELD,
            CHANGESET_ID_FIELD,
            SEQUENCE_NUM_FIELD,
            ENTRY_POINT_FIELD,
            FACT_TYPE_FIELD,
            EVENT_TIMESTAMP_FIELD,
            PAYLOAD_FIELD)
        .from(CHANGESET_EVENTS_TABLE)
        .where(condition)
        .orderBy(EVENT_TIMESTAMP_FIELD.asc(), SEQUENCE_NUM_FIELD.asc(), EVENT_ID_FIELD.asc())
        .offset(Math.toIntExact(Math.max(offset, 0)))
        .limit(limit)
        .fetch(this::toProjectedEvent);
  }

  public List<ProjectedEvent> listEvents(
      String entryPoint,
      Instant fromInclusive,
      Instant toExclusive,
      int limit,
      int offset,
      UUID changesetId) {
    Condition condition = noCondition();
    if (entryPoint != null && !entryPoint.isBlank()) {
      condition = condition.and(ENTRY_POINT_FIELD.eq(entryPoint));
    }
    if (fromInclusive != null) {
      condition = condition.and(EVENT_TIMESTAMP_FIELD.ge(toOffsetDateTime(fromInclusive)));
    }
    if (toExclusive != null) {
      condition = condition.and(EVENT_TIMESTAMP_FIELD.lt(toOffsetDateTime(toExclusive)));
    }
    if (changesetId != null) {
      condition = condition.and(CHANGESET_ID_FIELD.eq(changesetId));
    }

    return dsl.select(
            EVENT_ID_FIELD,
            CHANGESET_ID_FIELD,
            SEQUENCE_NUM_FIELD,
            ENTRY_POINT_FIELD,
            FACT_TYPE_FIELD,
            EVENT_TIMESTAMP_FIELD,
            PAYLOAD_FIELD)
        .from(CHANGESET_EVENTS_TABLE)
        .where(condition)
        .orderBy(EVENT_TIMESTAMP_FIELD.desc(), SEQUENCE_NUM_FIELD.desc(), EVENT_ID_FIELD.desc())
        .limit(limit)
        .offset(offset)
        .fetch(this::toProjectedEvent);
  }

  private ProjectedEvent toProjectedEvent(Record record) {
    return new ProjectedEvent(
        record.get(EVENT_ID_FIELD),
        record.get(CHANGESET_ID_FIELD),
        record.get(SEQUENCE_NUM_FIELD),
        record.get(ENTRY_POINT_FIELD),
        record.get(FACT_TYPE_FIELD),
        record.get(EVENT_TIMESTAMP_FIELD).toInstant(),
        deserializePayload(record.get(PAYLOAD_FIELD)));
  }

  private OffsetDateTime toOffsetDateTime(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  private String serializePayload(Map<String, Object> payload) {
    try {
      return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize event payload", e);
    }
  }

  private Map<String, Object> deserializePayload(JSONB payload) {
    try {
      return objectMapper.readValue(payload.data(), MAP_TYPE);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to deserialize event payload", e);
    }
  }

  public record ProjectedEvent(
      long eventId,
      UUID changesetId,
      long sequenceNum,
      String entryPoint,
      String factType,
      Instant eventTimestamp,
      Map<String, Object> payload) {}
}
