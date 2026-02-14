package com.sky.synome.rules;

import static org.jooq.impl.DSL.field;
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
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;

@ApplicationScoped
public class RuleVersionStore {

  private static final TypeReference<Map<String, String>> DRL_FILES_TYPE = new TypeReference<>() {};

  private static final Table<Record> RULE_VERSIONS_TABLE = table("rule_versions");
  private static final Field<UUID> VERSION_ID_FIELD = field("version_id", UUID.class);
  private static final Field<String> VERSION_LABEL_FIELD = field("version_label", String.class);
  private static final Field<JSONB> DRL_FILES_FIELD = field("drl_files", JSONB.class);
  private static final Field<String> CHECKSUM_FIELD = field("checksum", String.class);
  private static final Field<String> UPLOADED_BY_FIELD = field("uploaded_by", String.class);
  private static final Field<OffsetDateTime> UPLOADED_AT_FIELD =
      field("uploaded_at", OffsetDateTime.class);
  private static final Field<OffsetDateTime> ACTIVATED_AT_FIELD =
      field("activated_at", OffsetDateTime.class);
  private static final Field<OffsetDateTime> DEACTIVATED_AT_FIELD =
      field("deactivated_at", OffsetDateTime.class);
  private static final Field<Boolean> IS_ACTIVE_FIELD = field("is_active", Boolean.class);
  private static final Field<String> COMPILATION_LOG_FIELD = field("compilation_log", String.class);

  @Inject DSLContext dsl;

  @Inject ObjectMapper objectMapper;

  public RuleVersion create(NewRuleVersion newRuleVersion) {
    dsl.insertInto(RULE_VERSIONS_TABLE)
        .set(VERSION_ID_FIELD, newRuleVersion.versionId())
        .set(VERSION_LABEL_FIELD, newRuleVersion.versionLabel())
        .set(DRL_FILES_FIELD, toJsonb(newRuleVersion.drlFiles()))
        .set(CHECKSUM_FIELD, newRuleVersion.checksum())
        .set(UPLOADED_BY_FIELD, newRuleVersion.uploadedBy())
        .set(COMPILATION_LOG_FIELD, newRuleVersion.compilationLog())
        .execute();
    return findById(newRuleVersion.versionId())
        .orElseThrow(() -> new IllegalStateException("Uploaded rule version was not persisted"));
  }

  public Optional<RuleVersion> findById(UUID versionId) {
    return Optional.ofNullable(
            dsl.select(
                    VERSION_ID_FIELD,
                    VERSION_LABEL_FIELD,
                    DRL_FILES_FIELD,
                    CHECKSUM_FIELD,
                    UPLOADED_BY_FIELD,
                    UPLOADED_AT_FIELD,
                    ACTIVATED_AT_FIELD,
                    DEACTIVATED_AT_FIELD,
                    IS_ACTIVE_FIELD,
                    COMPILATION_LOG_FIELD)
                .from(RULE_VERSIONS_TABLE)
                .where(VERSION_ID_FIELD.eq(versionId))
                .fetchOne())
        .map(this::toRuleVersion);
  }

  public Optional<RuleVersion> findActive() {
    return Optional.ofNullable(
            dsl.select(
                    VERSION_ID_FIELD,
                    VERSION_LABEL_FIELD,
                    DRL_FILES_FIELD,
                    CHECKSUM_FIELD,
                    UPLOADED_BY_FIELD,
                    UPLOADED_AT_FIELD,
                    ACTIVATED_AT_FIELD,
                    DEACTIVATED_AT_FIELD,
                    IS_ACTIVE_FIELD,
                    COMPILATION_LOG_FIELD)
                .from(RULE_VERSIONS_TABLE)
                .where(IS_ACTIVE_FIELD.eq(true))
                .limit(1)
                .fetchOne())
        .map(this::toRuleVersion);
  }

  public List<RuleVersion> list(int limit, int offset) {
    int normalizedLimit = Math.max(1, Math.min(limit, 200));
    int normalizedOffset = Math.max(0, offset);
    return dsl.select(
            VERSION_ID_FIELD,
            VERSION_LABEL_FIELD,
            DRL_FILES_FIELD,
            CHECKSUM_FIELD,
            UPLOADED_BY_FIELD,
            UPLOADED_AT_FIELD,
            ACTIVATED_AT_FIELD,
            DEACTIVATED_AT_FIELD,
            IS_ACTIVE_FIELD,
            COMPILATION_LOG_FIELD)
        .from(RULE_VERSIONS_TABLE)
        .orderBy(UPLOADED_AT_FIELD.desc())
        .limit(normalizedLimit)
        .offset(normalizedOffset)
        .fetch(this::toRuleVersion);
  }

  public ActivationState activate(UUID versionId, Instant activatedAt) {
    RuleVersion target =
        findById(versionId)
            .orElseThrow(
                () ->
                    new RuleVersionException(
                        "RULE_VERSION_NOT_FOUND",
                        404,
                        "Rule version not found",
                        Map.of("versionId", versionId)));

    RuleVersion previous = findActive().orElse(null);
    if (target.isActive()) {
      return new ActivationState(
          previous == null ? null : previous.versionId(), target.versionId());
    }

    OffsetDateTime activatedAtUtc = OffsetDateTime.ofInstant(activatedAt, ZoneOffset.UTC);
    dsl.transaction(
        configuration -> {
          DSLContext tx = DSL.using(configuration);
          tx.update(RULE_VERSIONS_TABLE)
              .set(IS_ACTIVE_FIELD, false)
              .set(DEACTIVATED_AT_FIELD, activatedAtUtc)
              .where(IS_ACTIVE_FIELD.eq(true))
              .execute();

          int updated =
              tx.update(RULE_VERSIONS_TABLE)
                  .set(IS_ACTIVE_FIELD, true)
                  .set(ACTIVATED_AT_FIELD, activatedAtUtc)
                  .set(DEACTIVATED_AT_FIELD, (OffsetDateTime) null)
                  .where(VERSION_ID_FIELD.eq(versionId))
                  .execute();
          if (updated != 1) {
            throw new IllegalStateException("Unexpected activation update count=" + updated);
          }
        });

    return new ActivationState(previous == null ? null : previous.versionId(), versionId);
  }

  private RuleVersion toRuleVersion(Record record) {
    return new RuleVersion(
        record.get(VERSION_ID_FIELD),
        record.get(VERSION_LABEL_FIELD),
        fromJsonb(record.get(DRL_FILES_FIELD)),
        record.get(CHECKSUM_FIELD),
        record.get(UPLOADED_BY_FIELD),
        record.get(UPLOADED_AT_FIELD),
        record.get(ACTIVATED_AT_FIELD),
        record.get(DEACTIVATED_AT_FIELD),
        Boolean.TRUE.equals(record.get(IS_ACTIVE_FIELD)),
        record.get(COMPILATION_LOG_FIELD));
  }

  private JSONB toJsonb(Map<String, String> drlFiles) {
    try {
      return JSONB.valueOf(objectMapper.writeValueAsString(drlFiles));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize drl_files", e);
    }
  }

  private Map<String, String> fromJsonb(JSONB value) {
    if (value == null || value.data() == null || value.data().isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(value.data(), DRL_FILES_TYPE);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to deserialize drl_files", e);
    }
  }

  public record NewRuleVersion(
      UUID versionId,
      String versionLabel,
      Map<String, String> drlFiles,
      String checksum,
      String uploadedBy,
      String compilationLog) {}

  public record RuleVersion(
      UUID versionId,
      String versionLabel,
      Map<String, String> drlFiles,
      String checksum,
      String uploadedBy,
      OffsetDateTime uploadedAt,
      OffsetDateTime activatedAt,
      OffsetDateTime deactivatedAt,
      boolean isActive,
      String compilationLog) {}

  public record ActivationState(UUID previousVersionId, UUID activeVersionId) {}
}
