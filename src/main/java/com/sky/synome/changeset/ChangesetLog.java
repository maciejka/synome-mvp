package com.sky.synome.changeset;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.synome.api.dto.ChangesetResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.Table;

@ApplicationScoped
public class ChangesetLog {

  private static final Table<Record> CHANGESET_LOG_TABLE = table("changeset_log");
  private static final Field<UUID> CHANGESET_ID_FIELD = field("changeset_id", UUID.class);
  private static final Field<Long> SEQUENCE_NUM_FIELD = field("sequence_num", Long.class);
  private static final Field<JSONB> PAYLOAD_FIELD = field("payload", JSONB.class);
  private static final Field<String> CHECKSUM_FIELD = field("checksum", String.class);
  private static final Field<OffsetDateTime> APPLIED_AT_FIELD =
      field("applied_at", OffsetDateTime.class);
  private static final Field<Long> ENGINE_CLOCK_AT_FIELD = field("engine_clock_at", Long.class);
  private static final Field<Integer> RULES_FIRED_FIELD = field("rules_fired", Integer.class);
  private static final Field<Integer> DURATION_MS_FIELD = field("duration_ms", Integer.class);
  private static final Field<JSONB> RESPONSE_PAYLOAD_FIELD =
      field("response_payload", JSONB.class);

  @Inject DSLContext dsl;

  @Inject ObjectMapper objectMapper;

  public long append(Changeset changeset, int rulesFired, long durationMs) {
    return appendInternal(changeset, rulesFired, durationMs, null);
  }

  public long append(Changeset changeset, ChangesetResponse response) {
    if (response == null) {
      throw new IllegalArgumentException("response is required");
    }
    return appendInternal(changeset, response.rulesFired, response.durationMs, response);
  }

  public Optional<LoggedChangeset> findByChangesetId(UUID changesetId) {
    Record record =
        dsl.select(CHANGESET_ID_FIELD, SEQUENCE_NUM_FIELD, CHECKSUM_FIELD, RESPONSE_PAYLOAD_FIELD)
            .from(CHANGESET_LOG_TABLE)
            .where(CHANGESET_ID_FIELD.eq(changesetId))
            .fetchOne();

    if (record == null) {
      return Optional.empty();
    }

    return Optional.of(
        new LoggedChangeset(
            record.get(CHANGESET_ID_FIELD),
            record.get(SEQUENCE_NUM_FIELD),
            record.get(CHECKSUM_FIELD),
            readResponsePayload(record).orElse(null)));
  }

  public Optional<ReplayLookup> findReplay(Changeset changeset) {
    Optional<LoggedChangeset> existing = findByChangesetId(changeset.id());
    if (existing.isEmpty()) {
      return Optional.empty();
    }

    LoggedChangeset logged = existing.get();
    boolean checksumMatches = logged.checksum().equals(sha256(serializePayload(changeset)));
    return Optional.of(new ReplayLookup(checksumMatches, logged.responsePayload()));
  }

  private long appendInternal(
      Changeset changeset, int rulesFired, long durationMs, ChangesetResponse response) {
    String payloadJson = serializePayload(changeset);
    String checksum = sha256(payloadJson);

    Record record =
        dsl.insertInto(CHANGESET_LOG_TABLE)
            .set(CHANGESET_ID_FIELD, changeset.id())
            .set(PAYLOAD_FIELD, JSONB.valueOf(payloadJson))
            .set(CHECKSUM_FIELD, checksum)
            .set(APPLIED_AT_FIELD, OffsetDateTime.now())
            .set(ENGINE_CLOCK_AT_FIELD, 0L)
            .set(RULES_FIRED_FIELD, rulesFired)
            .set(DURATION_MS_FIELD, Math.toIntExact(durationMs))
            .returning(SEQUENCE_NUM_FIELD)
            .fetchOne();
    if (record == null) {
      throw new IllegalStateException("INSERT INTO changeset_log returned no record");
    }
    long sequenceNum = record.get(SEQUENCE_NUM_FIELD);

    if (response != null) {
      persistReplayResponse(sequenceNum, response);
    }

    return sequenceNum;
  }

  private void persistReplayResponse(long sequenceNum, ChangesetResponse response) {
    ChangesetResponse replayResponse = copyResponseWithSequence(response, sequenceNum);
    int updated =
        dsl.update(CHANGESET_LOG_TABLE)
            .set(RESPONSE_PAYLOAD_FIELD, JSONB.valueOf(serializeResponse(replayResponse)))
            .where(SEQUENCE_NUM_FIELD.eq(sequenceNum))
            .execute();
    if (updated != 1) {
      throw new IllegalStateException(
          "Failed to persist response_payload for sequence " + sequenceNum);
    }
  }

  private Optional<ChangesetResponse> readResponsePayload(Record record) {
    JSONB responsePayload = record.get(RESPONSE_PAYLOAD_FIELD);
    if (responsePayload == null) {
      return Optional.empty();
    }
    return Optional.of(deserializeResponse(responsePayload));
  }

  private ChangesetResponse copyResponseWithSequence(
      ChangesetResponse response, long sequenceNum) {
    ChangesetResponse copy = new ChangesetResponse();
    copy.changesetId = response.changesetId;
    copy.sequenceNum = sequenceNum;
    copy.status = response.status;
    copy.rulesFired = response.rulesFired;
    copy.durationMs = response.durationMs;
    copy.effects = response.effects;
    copy.newDerivedFacts = response.newDerivedFacts;
    return copy;
  }

  private String serializePayload(Changeset changeset) {
    try {
      return objectMapper.writeValueAsString(changeset);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize changeset", e);
    }
  }

  private String serializeResponse(ChangesetResponse response) {
    try {
      return objectMapper.writeValueAsString(response);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize changeset response", e);
    }
  }

  private ChangesetResponse deserializeResponse(JSONB responsePayload) {
    try {
      return objectMapper.readValue(responsePayload.data(), ChangesetResponse.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to deserialize response_payload", e);
    }
  }

  private String sha256(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  public record LoggedChangeset(
      UUID changesetId, long sequenceNum, String checksum, ChangesetResponse responsePayload) {}

  public record ReplayLookup(boolean checksumMatches, ChangesetResponse responsePayload) {}
}
