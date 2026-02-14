package com.sky.synome.provenance;

import static org.jooq.impl.DSL.condition;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.Table;

@ApplicationScoped
public class ProvenanceRepository {

  private static final Table<Record> FACT_PROVENANCE_TABLE = table("fact_provenance");
  private static final Field<String> FACT_ID_FIELD = field("fact_id", String.class);
  private static final Field<String> FACT_TYPE_FIELD = field("fact_type", String.class);
  private static final Field<String> FACT_KEY_FIELD = field("fact_key", String.class);
  private static final Field<String> PRODUCED_BY_RULE_FIELD =
      field("produced_by_rule", String.class);
  private static final Field<String> INSERTION_TYPE_FIELD = field("insertion_type", String.class);
  private static final Field<String[]> INPUT_FACT_IDS_FIELD =
      field("input_fact_ids", String[].class);
  private static final Field<UUID> CHANGESET_ID_FIELD = field("changeset_id", UUID.class);
  private static final Field<Instant> CREATED_AT_FIELD = field("created_at", Instant.class);
  private static final Field<Instant> RETRACTED_AT_FIELD = field("retracted_at", Instant.class);
  private static final Field<String> RETRACTION_REASON_FIELD =
      field("retraction_reason", String.class);

  private static final Table<Record> FACT_MODIFICATIONS_TABLE = table("fact_modifications");
  private static final Field<Long> MOD_ID_FIELD = field("id", Long.class);
  private static final Field<String> MOD_FACT_ID_FIELD = field("fact_id", String.class);
  private static final Field<String> RULE_NAME_FIELD = field("rule_name", String.class);
  private static final Field<Instant> MODIFIED_AT_FIELD = field("modified_at", Instant.class);
  private static final Field<JSONB> BEFORE_STATE_FIELD = field("before_state", JSONB.class);
  private static final Field<JSONB> AFTER_STATE_FIELD = field("after_state", JSONB.class);
  private static final Field<String[]> TRIGGERING_FACTS_FIELD =
      field("triggering_facts", String[].class);

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  @Inject DSLContext dsl;

  @Inject ObjectMapper objectMapper;

  public void upsertFacts(List<ProvenanceFactRecord> facts) {
    if (facts.isEmpty()) {
      return;
    }

    dsl.transaction(
        configuration -> {
          DSLContext tx = configuration.dsl();
          for (ProvenanceFactRecord record : facts) {
            tx.insertInto(FACT_PROVENANCE_TABLE)
                .set(FACT_ID_FIELD, record.factId())
                .set(FACT_TYPE_FIELD, record.factType())
                .set(FACT_KEY_FIELD, record.factKey())
                .set(PRODUCED_BY_RULE_FIELD, record.producedByRule())
                .set(INSERTION_TYPE_FIELD, record.insertionType().name())
                .set(INPUT_FACT_IDS_FIELD, record.inputFactIds().toArray(String[]::new))
                .set(CHANGESET_ID_FIELD, record.changesetId())
                .set(CREATED_AT_FIELD, record.createdAt())
                .set(RETRACTED_AT_FIELD, record.retractedAt())
                .set(RETRACTION_REASON_FIELD, record.retractionReason())
                .onConflict(FACT_ID_FIELD)
                .doUpdate()
                .set(FACT_TYPE_FIELD, excluded(FACT_TYPE_FIELD))
                .set(FACT_KEY_FIELD, excluded(FACT_KEY_FIELD))
                .set(PRODUCED_BY_RULE_FIELD, excluded(PRODUCED_BY_RULE_FIELD))
                .set(INSERTION_TYPE_FIELD, excluded(INSERTION_TYPE_FIELD))
                .set(INPUT_FACT_IDS_FIELD, excluded(INPUT_FACT_IDS_FIELD))
                .set(CHANGESET_ID_FIELD, excluded(CHANGESET_ID_FIELD))
                .set(CREATED_AT_FIELD, excluded(CREATED_AT_FIELD))
                .set(RETRACTED_AT_FIELD, excluded(RETRACTED_AT_FIELD))
                .set(RETRACTION_REASON_FIELD, excluded(RETRACTION_REASON_FIELD))
                .execute();
          }
        });
  }

  public void applyRetractions(List<ProvenanceRetraction> retractions) {
    if (retractions.isEmpty()) {
      return;
    }

    dsl.transaction(
        configuration -> {
          DSLContext tx = configuration.dsl();
          for (ProvenanceRetraction retraction : retractions) {
            tx.update(FACT_PROVENANCE_TABLE)
                .set(RETRACTED_AT_FIELD, retraction.retractedAt())
                .set(RETRACTION_REASON_FIELD, retraction.retractionReason())
                .where(FACT_ID_FIELD.eq(retraction.factId()))
                .execute();
          }
        });
  }

  public void insertModifications(List<ProvenanceModification> modifications) {
    if (modifications.isEmpty()) {
      return;
    }

    dsl.transaction(
        configuration -> {
          DSLContext tx = configuration.dsl();
          for (ProvenanceModification modification : modifications) {
            tx.insertInto(FACT_MODIFICATIONS_TABLE)
                .set(MOD_FACT_ID_FIELD, modification.factId())
                .set(RULE_NAME_FIELD, modification.ruleName())
                .set(MODIFIED_AT_FIELD, modification.modifiedAt())
                .set(BEFORE_STATE_FIELD, toJsonb(modification.beforeState()))
                .set(AFTER_STATE_FIELD, toJsonb(modification.afterState()))
                .set(TRIGGERING_FACTS_FIELD, modification.triggeringFacts().toArray(String[]::new))
                .execute();
          }
        });
  }

  public Optional<ProvenanceFactRecord> findFactById(String factId) {
    Record record =
        dsl.select(
                FACT_ID_FIELD,
                FACT_TYPE_FIELD,
                FACT_KEY_FIELD,
                PRODUCED_BY_RULE_FIELD,
                INSERTION_TYPE_FIELD,
                INPUT_FACT_IDS_FIELD,
                CHANGESET_ID_FIELD,
                CREATED_AT_FIELD,
                RETRACTED_AT_FIELD,
                RETRACTION_REASON_FIELD)
            .from(FACT_PROVENANCE_TABLE)
            .where(FACT_ID_FIELD.eq(factId))
            .fetchOne();

    return record == null ? Optional.empty() : Optional.of(toFactRecord(record));
  }

  public List<ProvenanceFactRecord> findFactsByIds(Collection<String> factIds) {
    if (factIds == null || factIds.isEmpty()) {
      return List.of();
    }

    return dsl.select(
            FACT_ID_FIELD,
            FACT_TYPE_FIELD,
            FACT_KEY_FIELD,
            PRODUCED_BY_RULE_FIELD,
            INSERTION_TYPE_FIELD,
            INPUT_FACT_IDS_FIELD,
            CHANGESET_ID_FIELD,
            CREATED_AT_FIELD,
            RETRACTED_AT_FIELD,
            RETRACTION_REASON_FIELD)
        .from(FACT_PROVENANCE_TABLE)
        .where(FACT_ID_FIELD.in(factIds))
        .fetch(this::toFactRecord);
  }

  public List<ProvenanceModification> listModifications(String factId, int limit) {
    return dsl.select(
            MOD_ID_FIELD,
            MOD_FACT_ID_FIELD,
            RULE_NAME_FIELD,
            MODIFIED_AT_FIELD,
            BEFORE_STATE_FIELD,
            AFTER_STATE_FIELD,
            TRIGGERING_FACTS_FIELD)
        .from(FACT_MODIFICATIONS_TABLE)
        .where(MOD_FACT_ID_FIELD.eq(factId))
        .orderBy(MOD_ID_FIELD.desc())
        .limit(Math.max(1, limit))
        .fetch(this::toModificationRecord);
  }

  public List<ProvenanceFactRecord> search(
      String factType,
      String factKeyPrefix,
      String producedByRule,
      Integer limit,
      Integer offset,
      Boolean activeOnly) {
    Condition where = condition("1 = 1");
    if (factType != null && !factType.isBlank()) {
      where = where.and(FACT_TYPE_FIELD.eq(factType));
    }
    if (factKeyPrefix != null && !factKeyPrefix.isBlank()) {
      where = where.and(FACT_KEY_FIELD.like(factKeyPrefix + "%"));
    }
    if (producedByRule != null && !producedByRule.isBlank()) {
      where = where.and(PRODUCED_BY_RULE_FIELD.eq(producedByRule));
    }
    if (Boolean.TRUE.equals(activeOnly)) {
      where = where.and(RETRACTED_AT_FIELD.isNull());
    }

    int cappedLimit = Math.max(1, Math.min(limit == null ? 50 : limit, 500));
    int safeOffset = Math.max(0, offset == null ? 0 : offset);

    return dsl.select(
            FACT_ID_FIELD,
            FACT_TYPE_FIELD,
            FACT_KEY_FIELD,
            PRODUCED_BY_RULE_FIELD,
            INSERTION_TYPE_FIELD,
            INPUT_FACT_IDS_FIELD,
            CHANGESET_ID_FIELD,
            CREATED_AT_FIELD,
            RETRACTED_AT_FIELD,
            RETRACTION_REASON_FIELD)
        .from(FACT_PROVENANCE_TABLE)
        .where(where)
        .orderBy(CREATED_AT_FIELD.desc(), FACT_ID_FIELD.asc())
        .limit(cappedLimit)
        .offset(safeOffset)
        .fetch(this::toFactRecord);
  }

  public List<ProvenanceFactRecord> findDependents(String inputFactId) {
    return dsl.select(
            FACT_ID_FIELD,
            FACT_TYPE_FIELD,
            FACT_KEY_FIELD,
            PRODUCED_BY_RULE_FIELD,
            INSERTION_TYPE_FIELD,
            INPUT_FACT_IDS_FIELD,
            CHANGESET_ID_FIELD,
            CREATED_AT_FIELD,
            RETRACTED_AT_FIELD,
            RETRACTION_REASON_FIELD)
        .from(FACT_PROVENANCE_TABLE)
        .where(condition("{0} = ANY({1})", inputFactId, INPUT_FACT_IDS_FIELD))
        .orderBy(CREATED_AT_FIELD.asc(), FACT_ID_FIELD.asc())
        .fetch(this::toFactRecord);
  }

  private ProvenanceFactRecord toFactRecord(Record record) {
    String[] inputFactIds = record.get(INPUT_FACT_IDS_FIELD);
    String insertionType = record.get(INSERTION_TYPE_FIELD);
    ProvenanceInsertionType type =
        insertionType == null
            ? ProvenanceInsertionType.DERIVED_FACT
            : ProvenanceInsertionType.valueOf(insertionType);

    return new ProvenanceFactRecord(
        record.get(FACT_ID_FIELD),
        record.get(FACT_TYPE_FIELD),
        record.get(FACT_KEY_FIELD),
        record.get(PRODUCED_BY_RULE_FIELD),
        type,
        inputFactIds == null ? List.of() : List.of(inputFactIds),
        record.get(CHANGESET_ID_FIELD),
        record.get(CREATED_AT_FIELD),
        record.get(RETRACTED_AT_FIELD),
        record.get(RETRACTION_REASON_FIELD));
  }

  private ProvenanceModification toModificationRecord(Record record) {
    String[] triggeringFacts = record.get(TRIGGERING_FACTS_FIELD);
    return new ProvenanceModification(
        record.get(MOD_FACT_ID_FIELD),
        record.get(RULE_NAME_FIELD),
        record.get(MODIFIED_AT_FIELD),
        fromJsonb(record.get(BEFORE_STATE_FIELD)),
        fromJsonb(record.get(AFTER_STATE_FIELD)),
        triggeringFacts == null ? List.of() : List.of(triggeringFacts));
  }

  private JSONB toJsonb(Map<String, Object> data) {
    try {
      return JSONB.jsonb(objectMapper.writeValueAsString(data == null ? Map.of() : data));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize provenance payload", e);
    }
  }

  private Map<String, Object> fromJsonb(JSONB jsonb) {
    if (jsonb == null || jsonb.data() == null || jsonb.data().isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(jsonb.data(), MAP_TYPE);
    } catch (Exception e) {
      return Map.of("raw", jsonb.data());
    }
  }
}
