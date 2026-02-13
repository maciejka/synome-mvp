package com.sky.synome.checkpoint;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.Table;

@ApplicationScoped
public class CheckpointStore {

  private static final Table<Record> CHECKPOINT_TABLE = table("checkpoints");

  private static final Field<UUID> CHECKPOINT_ID_FIELD = field("checkpoint_id", UUID.class);
  private static final Field<Long> SEQUENCE_NUM_FIELD = field("sequence_num", Long.class);
  private static final Field<UUID> RULE_VERSION_ID_FIELD = field("rule_version_id", UUID.class);
  private static final Field<Long> CLOCK_MILLIS_FIELD = field("clock_millis", Long.class);
  private static final Field<Integer> FACT_COUNT_FIELD = field("fact_count", Integer.class);
  private static final Field<String> BLOB_FORMAT_FIELD = field("blob_format", String.class);
  private static final Field<byte[]> FACT_BLOB_FIELD = field("fact_blob", byte[].class);
  private static final Field<byte[]> FACT_REGISTRY_FIELD = field("fact_registry", byte[].class);
  private static final Field<JSONB> ENGINE_METADATA_FIELD = field("engine_metadata", JSONB.class);
  private static final Field<OffsetDateTime> CREATED_AT_FIELD =
      field("created_at", OffsetDateTime.class);
  private static final Field<Long> SIZE_BYTES_FIELD = field("size_bytes", Long.class);

  private static final TypeReference<Map<String, Object>> METADATA_MAP_TYPE =
      new TypeReference<>() {};

  @Inject DSLContext dsl;

  @Inject ObjectMapper objectMapper;

  public CheckpointRecord create(NewCheckpoint checkpoint) {
    Record record =
        dsl.insertInto(CHECKPOINT_TABLE)
            .set(CHECKPOINT_ID_FIELD, checkpoint.checkpointId())
            .set(SEQUENCE_NUM_FIELD, checkpoint.sequenceNum())
            .set(RULE_VERSION_ID_FIELD, checkpoint.ruleVersionId())
            .set(CLOCK_MILLIS_FIELD, checkpoint.clockMillis())
            .set(FACT_COUNT_FIELD, checkpoint.factCount())
            .set(BLOB_FORMAT_FIELD, checkpoint.blobFormat())
            .set(FACT_BLOB_FIELD, checkpoint.factBlob())
            .set(FACT_REGISTRY_FIELD, checkpoint.factRegistry())
            .set(
                ENGINE_METADATA_FIELD,
                JSONB.valueOf(serializeMetadata(checkpoint.engineMetadata())))
            .set(SIZE_BYTES_FIELD, checkpoint.sizeBytes())
            .returning(
                CHECKPOINT_ID_FIELD,
                SEQUENCE_NUM_FIELD,
                RULE_VERSION_ID_FIELD,
                CLOCK_MILLIS_FIELD,
                FACT_COUNT_FIELD,
                BLOB_FORMAT_FIELD,
                ENGINE_METADATA_FIELD,
                CREATED_AT_FIELD,
                SIZE_BYTES_FIELD)
            .fetchOne();
    if (record == null) {
      throw new CheckpointException("INSERT INTO checkpoints returned no record");
    }
    return toCheckpointRecord(record);
  }

  public Optional<PersistedCheckpoint> findLatestWithBlob() {
    Record record =
        dsl.select(
                CHECKPOINT_ID_FIELD,
                SEQUENCE_NUM_FIELD,
                RULE_VERSION_ID_FIELD,
                CLOCK_MILLIS_FIELD,
                FACT_COUNT_FIELD,
                BLOB_FORMAT_FIELD,
                FACT_BLOB_FIELD,
                FACT_REGISTRY_FIELD,
                ENGINE_METADATA_FIELD,
                CREATED_AT_FIELD,
                SIZE_BYTES_FIELD)
            .from(CHECKPOINT_TABLE)
            .orderBy(SEQUENCE_NUM_FIELD.desc())
            .limit(1)
            .fetchOne();
    if (record == null) {
      return Optional.empty();
    }
    return Optional.of(toPersistedCheckpoint(record));
  }

  public Optional<PersistedCheckpoint> findByIdWithBlob(UUID checkpointId) {
    Record record =
        dsl.select(
                CHECKPOINT_ID_FIELD,
                SEQUENCE_NUM_FIELD,
                RULE_VERSION_ID_FIELD,
                CLOCK_MILLIS_FIELD,
                FACT_COUNT_FIELD,
                BLOB_FORMAT_FIELD,
                FACT_BLOB_FIELD,
                FACT_REGISTRY_FIELD,
                ENGINE_METADATA_FIELD,
                CREATED_AT_FIELD,
                SIZE_BYTES_FIELD)
            .from(CHECKPOINT_TABLE)
            .where(CHECKPOINT_ID_FIELD.eq(checkpointId))
            .fetchOne();
    if (record == null) {
      return Optional.empty();
    }
    return Optional.of(toPersistedCheckpoint(record));
  }

  public List<CheckpointRecord> list(int limit, int offset) {
    return dsl.select(
            CHECKPOINT_ID_FIELD,
            SEQUENCE_NUM_FIELD,
            RULE_VERSION_ID_FIELD,
            CLOCK_MILLIS_FIELD,
            FACT_COUNT_FIELD,
            BLOB_FORMAT_FIELD,
            ENGINE_METADATA_FIELD,
            CREATED_AT_FIELD,
            SIZE_BYTES_FIELD)
        .from(CHECKPOINT_TABLE)
        .orderBy(SEQUENCE_NUM_FIELD.desc())
        .limit(limit)
        .offset(offset)
        .fetch(this::toCheckpointRecord);
  }

  public int pruneKeeping(int retainCount) {
    if (retainCount <= 0) {
      return dsl.deleteFrom(CHECKPOINT_TABLE).execute();
    }
    List<UUID> keepIds =
        dsl.select(CHECKPOINT_ID_FIELD)
            .from(CHECKPOINT_TABLE)
            .orderBy(SEQUENCE_NUM_FIELD.desc())
            .limit(retainCount)
            .fetch(CHECKPOINT_ID_FIELD);
    if (keepIds.isEmpty()) {
      return 0;
    }
    return dsl.deleteFrom(CHECKPOINT_TABLE).where(CHECKPOINT_ID_FIELD.notIn(keepIds)).execute();
  }

  private CheckpointRecord toCheckpointRecord(Record record) {
    return new CheckpointRecord(
        record.get(CHECKPOINT_ID_FIELD),
        record.get(SEQUENCE_NUM_FIELD),
        record.get(RULE_VERSION_ID_FIELD),
        record.get(CLOCK_MILLIS_FIELD),
        record.get(FACT_COUNT_FIELD),
        record.get(BLOB_FORMAT_FIELD),
        readMetadata(record.get(ENGINE_METADATA_FIELD)),
        record.get(CREATED_AT_FIELD),
        record.get(SIZE_BYTES_FIELD));
  }

  private PersistedCheckpoint toPersistedCheckpoint(Record record) {
    return new PersistedCheckpoint(
        toCheckpointRecord(record), record.get(FACT_BLOB_FIELD), record.get(FACT_REGISTRY_FIELD));
  }

  private Map<String, Object> readMetadata(JSONB jsonb) {
    if (jsonb == null) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(jsonb.data(), METADATA_MAP_TYPE);
    } catch (JsonProcessingException e) {
      throw new CheckpointException("Failed to deserialize checkpoint engine_metadata", e);
    }
  }

  private String serializeMetadata(Map<String, Object> metadata) {
    try {
      return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
    } catch (JsonProcessingException e) {
      throw new CheckpointException("Failed to serialize checkpoint engine_metadata", e);
    }
  }

  public record NewCheckpoint(
      UUID checkpointId,
      long sequenceNum,
      UUID ruleVersionId,
      long clockMillis,
      int factCount,
      String blobFormat,
      byte[] factBlob,
      byte[] factRegistry,
      Map<String, Object> engineMetadata,
      long sizeBytes) {}

  public record PersistedCheckpoint(
      CheckpointRecord record, byte[] factBlob, byte[] factRegistry) {}
}
