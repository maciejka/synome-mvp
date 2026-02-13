package com.sky.synome.checkpoint;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import com.sky.synome.changeset.ChangesetLog;
import com.sky.synome.config.EngineConfig;
import com.sky.synome.core.EngineSession;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jboss.logging.Logger;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;

@ApplicationScoped
public class CheckpointService {

  private static final Logger LOG = Logger.getLogger(CheckpointService.class);
  private static final int CHECKPOINT_SCHEMA_VERSION = 1;

  private static final Table<Record> RULE_VERSIONS_TABLE = table("rule_versions");
  private static final Field<UUID> VERSION_ID_FIELD = field("version_id", UUID.class);
  private static final Field<Boolean> IS_ACTIVE_FIELD = field("is_active", Boolean.class);

  @Inject EngineSession engineSession;

  @Inject EngineConfig engineConfig;

  @Inject ChangesetLog changesetLog;

  @Inject CheckpointStore checkpointStore;

  @Inject CheckpointSerializer checkpointSerializer;

  @Inject DSLContext dsl;

  public CheckpointRecord createCheckpoint(String reason) {
    String checkpointIdForLogs = "pending";
    long checkpointSequenceForLogs = 0L;
    int factCountForLogs = 0;
    long sizeBytesForLogs = 0L;
    String phase = "acquire_lock";
    String normalizedReason = normalizeReason(reason);
    long start = System.currentTimeMillis();

    LOG.infof(
        "checkpoint.lifecycle event=checkpoint.create.start checkpointId=%s checkpointSequence=%d "
            + "factCount=%d sizeBytes=%d durationMs=%d reason=%s phase=%s",
        checkpointIdForLogs,
        checkpointSequenceForLogs,
        factCountForLogs,
        sizeBytesForLogs,
        0,
        normalizedReason,
        phase);

    if (!engineConfig.checkpointEnabled()) {
      throw new CheckpointException(
          "Checkpointing is disabled by configuration",
          Map.of("operation", "checkpoint_create", "phase", "configuration"));
    }

    var lock = engineSession.sessionLock();
    if (!lock.tryAcquire(engineConfig.lockTimeoutMs())) {
      long durationMs = System.currentTimeMillis() - start;
      LOG.errorf(
          "checkpoint.lifecycle event=checkpoint.create.failure "
              + "checkpointId=%s checkpointSequence=%d "
              + "factCount=%d sizeBytes=%d durationMs=%d reason=%s phase=%s errorType=%s",
          checkpointIdForLogs,
          checkpointSequenceForLogs,
          factCountForLogs,
          sizeBytesForLogs,
          durationMs,
          normalizedReason,
          phase,
          "LockTimeout");
      throw new CheckpointException(
          "Could not acquire session lock to create checkpoint within "
              + engineConfig.lockTimeoutMs()
              + "ms",
          Map.of(
              "operation",
              "checkpoint_create",
              "phase",
              phase,
              "lockTimeoutMs",
              engineConfig.lockTimeoutMs()));
    }

    try {
      phase = "snapshot";
      List<EngineSession.RestorableFact> sessionFacts =
          new ArrayList<>(engineSession.snapshotBaseFacts());
      sessionFacts.sort(Comparator.comparing(EngineSession.RestorableFact::factKey));

      List<CheckpointFact> facts = new ArrayList<>(sessionFacts.size());
      Map<String, CheckpointRegistryEntry> registry = new LinkedHashMap<>();
      for (EngineSession.RestorableFact fact : sessionFacts) {
        Map<String, Object> data = fact.data() == null ? Map.of() : Map.copyOf(fact.data());
        facts.add(new CheckpointFact(fact.factKey(), fact.factType(), data));
        registry.put(fact.factKey(), new CheckpointRegistryEntry(fact.factType(), data));
      }

      byte[] factBlob = checkpointSerializer.serializeFacts(facts);
      byte[] registryBlob = checkpointSerializer.serializeRegistry(registry);

      phase = "resolve_boundary";
      long sequenceNum = changesetLog.latestFinalizedSequence().orElse(0L);
      long clockMillis = engineSession.currentClockMillis();
      phase = "resolve_rule_version";
      UUID ruleVersionId = resolveRuleVersionId();
      UUID checkpointId = UUID.randomUUID();
      checkpointIdForLogs = checkpointId.toString();
      checkpointSequenceForLogs = sequenceNum;
      factCountForLogs = facts.size();

      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put("schemaVersion", CHECKPOINT_SCHEMA_VERSION);
      metadata.put("reason", normalizedReason);
      metadata.put("capturedAt", Instant.now().toString());
      metadata.put("rulesPath", engineConfig.rulesPath());
      metadata.put("rulesFile", engineConfig.defaultRulesFile());

      long sizeBytes = factBlob.length + registryBlob.length;
      sizeBytesForLogs = sizeBytes;
      CheckpointStore.NewCheckpoint checkpoint =
          new CheckpointStore.NewCheckpoint(
              checkpointId,
              sequenceNum,
              ruleVersionId,
              clockMillis,
              facts.size(),
              checkpointSerializer.format(),
              factBlob,
              registryBlob,
              metadata,
              sizeBytes);

      phase = "persist";
      CheckpointRecord created = checkpointStore.create(checkpoint);
      phase = "prune_retention";
      checkpointStore.pruneKeeping(engineConfig.checkpointRetainCount());

      long durationMs = System.currentTimeMillis() - start;
      LOG.infof(
          "checkpoint.lifecycle event=checkpoint.create.success "
              + "checkpointId=%s checkpointSequence=%d "
              + "factCount=%d sizeBytes=%d durationMs=%d reason=%s phase=completed",
          created.checkpointId(),
          created.sequenceNum(),
          created.factCount(),
          created.sizeBytes(),
          durationMs,
          metadata.get("reason"));
      return created;
    } catch (RuntimeException e) {
      long durationMs = System.currentTimeMillis() - start;
      LOG.errorf(
          e,
          "checkpoint.lifecycle event=checkpoint.create.failure "
              + "checkpointId=%s checkpointSequence=%d "
              + "factCount=%d sizeBytes=%d durationMs=%d reason=%s phase=%s errorType=%s",
          checkpointIdForLogs,
          checkpointSequenceForLogs,
          factCountForLogs,
          sizeBytesForLogs,
          durationMs,
          normalizedReason,
          phase,
          e.getClass().getSimpleName());
      throw new CheckpointException(
          "Checkpoint creation failed in phase="
              + phase
              + " checkpointId="
              + checkpointIdForLogs
              + ": "
              + e.getMessage(),
          e,
          Map.of(
              "operation",
              "checkpoint_create",
              "phase",
              phase,
              "checkpointId",
              checkpointIdForLogs,
              "checkpointSequence",
              checkpointSequenceForLogs,
              "reason",
              normalizedReason));
    } finally {
      lock.release();
    }
  }

  public Optional<CheckpointRecord> findLatest() {
    return checkpointStore.findLatestWithBlob().map(CheckpointStore.PersistedCheckpoint::record);
  }

  public Optional<CheckpointRecord> findById(UUID checkpointId) {
    return checkpointStore
        .findByIdWithBlob(checkpointId)
        .map(CheckpointStore.PersistedCheckpoint::record);
  }

  public List<CheckpointRecord> list(int limit, int offset) {
    return checkpointStore.list(limit, offset);
  }

  public Optional<CheckpointSnapshot> loadLatestSnapshot() {
    Optional<CheckpointStore.PersistedCheckpoint> latest = checkpointStore.findLatestWithBlob();
    if (latest.isEmpty()) {
      return Optional.empty();
    }

    CheckpointStore.PersistedCheckpoint persisted = latest.get();
    CheckpointRecord record = persisted.record();

    List<CheckpointFact> facts = checkpointSerializer.deserializeFacts(persisted.factBlob());
    Map<String, CheckpointRegistryEntry> registry =
        checkpointSerializer.deserializeRegistry(persisted.factRegistry());

    validateSnapshotConsistency(record, facts, registry);

    List<EngineSession.RestorableFact> restorableFacts =
        facts.stream()
            .map(
                fact ->
                    new EngineSession.RestorableFact(
                        fact.factKey(), fact.factType(), fact.data(), null))
            .toList();

    return Optional.of(
        new CheckpointSnapshot(
            record.checkpointId(),
            record.sequenceNum(),
            record.clockMillis(),
            record.ruleVersionId(),
            record.createdAt(),
            restorableFacts));
  }

  private void validateSnapshotConsistency(
      CheckpointRecord record,
      List<CheckpointFact> facts,
      Map<String, CheckpointRegistryEntry> registry) {
    if (record.factCount() != facts.size()) {
      throw new CheckpointException(
          "Checkpoint fact_count mismatch for "
              + record.checkpointId()
              + ": stored="
              + record.factCount()
              + ", decoded="
              + facts.size());
    }
    if (registry.size() != facts.size()) {
      throw new CheckpointException(
          "Checkpoint registry size mismatch for "
              + record.checkpointId()
              + ": registry="
              + registry.size()
              + ", facts="
              + facts.size());
    }
    for (CheckpointFact fact : facts) {
      CheckpointRegistryEntry registryEntry = registry.get(fact.factKey());
      if (registryEntry == null) {
        throw new CheckpointException(
            "Checkpoint registry missing factKey="
                + fact.factKey()
                + " for "
                + record.checkpointId());
      }
      if (!fact.factType().equals(registryEntry.factType())) {
        throw new CheckpointException(
            "Checkpoint factType mismatch for factKey="
                + fact.factKey()
                + " in checkpoint "
                + record.checkpointId());
      }
    }
  }

  private UUID resolveRuleVersionId() {
    Record active =
        dsl.select(VERSION_ID_FIELD)
            .from(RULE_VERSIONS_TABLE)
            .where(IS_ACTIVE_FIELD.eq(true))
            .limit(1)
            .fetchOne();
    if (active != null && active.get(VERSION_ID_FIELD) != null) {
      return active.get(VERSION_ID_FIELD);
    }

    String seed = engineConfig.rulesPath() + "/" + engineConfig.defaultRulesFile();
    return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
  }

  private String normalizeReason(String reason) {
    if (reason == null || reason.isBlank()) {
      return "manual";
    }
    return reason;
  }
}
