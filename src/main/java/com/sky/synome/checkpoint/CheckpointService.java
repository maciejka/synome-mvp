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
    if (!engineConfig.checkpointEnabled()) {
      throw new CheckpointException("Checkpointing is disabled by configuration");
    }

    var lock = engineSession.sessionLock();
    if (!lock.tryAcquire(engineConfig.lockTimeoutMs())) {
      throw new CheckpointException("Could not acquire session lock to create checkpoint");
    }

    try {
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

      long sequenceNum = changesetLog.latestFinalizedSequence().orElse(0L);
      long clockMillis = engineSession.currentClockMillis();
      UUID ruleVersionId = resolveRuleVersionId();
      UUID checkpointId = UUID.randomUUID();

      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put("schemaVersion", CHECKPOINT_SCHEMA_VERSION);
      metadata.put("reason", reason == null ? "manual" : reason);
      metadata.put("capturedAt", Instant.now().toString());
      metadata.put("rulesPath", engineConfig.rulesPath());
      metadata.put("rulesFile", engineConfig.defaultRulesFile());

      long sizeBytes = factBlob.length + registryBlob.length;
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

      CheckpointRecord created = checkpointStore.create(checkpoint);
      checkpointStore.pruneKeeping(engineConfig.checkpointRetainCount());

      LOG.infof(
          "Created checkpoint %s at sequence=%d facts=%d clock=%dms size=%dB reason=%s",
          created.checkpointId(),
          created.sequenceNum(),
          created.factCount(),
          created.clockMillis(),
          created.sizeBytes(),
          metadata.get("reason"));
      return created;
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
}
