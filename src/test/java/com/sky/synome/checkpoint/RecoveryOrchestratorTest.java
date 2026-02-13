package com.sky.synome.checkpoint;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sky.synome.api.dto.ChangesetResponse;
import com.sky.synome.changeset.Changeset;
import com.sky.synome.changeset.ChangesetAction;
import com.sky.synome.changeset.ChangesetEntry;
import com.sky.synome.changeset.ChangesetProcessor;
import com.sky.synome.changeset.EntryKind;
import com.sky.synome.core.EngineSession;
import com.sky.synome.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(RecoveryEnabledTestProfile.class)
class RecoveryOrchestratorTest {

  @Inject ChangesetProcessor changesetProcessor;

  @Inject CheckpointService checkpointService;

  @Inject RecoveryOrchestrator recoveryOrchestrator;

  @Inject EngineSession engineSession;

  @Inject DSLContext dsl;

  @Test
  void recoveryRestoresCheckpointAndReplaysTailWithoutAppendingLogs() {
    resetState();
    String factKeyBase = "customer:REC-" + UUID.randomUUID();
    String factKeyTail = "customer:REC-TAIL-" + UUID.randomUUID();

    changesetProcessor.process(changeset(UUID.randomUUID(), factKeyBase, "Recovery Base"));
    checkpointService.createCheckpoint("test-recovery");

    changesetProcessor.process(changeset(UUID.randomUUID(), factKeyTail, "Recovery Tail"));
    changesetProcessor.process(changeset(UUID.randomUUID(), factKeyBase, "Recovery Base Updated"));

    int logRowsBeforeRecovery = dsl.fetchCount(table("changeset_log"));

    engineSession.restoreFromSnapshot(List.of(), 0L, true);
    assertFalse(engineSession.factRegistry().contains(factKeyBase));
    assertFalse(engineSession.factRegistry().contains(factKeyTail));

    RecoveryOrchestrator.RecoveryResult result = recoveryOrchestrator.recover(engineSession);

    assertTrue(result.recoveredFromCheckpoint());
    assertTrue(result.replayedChangesets() >= 2);
    assertTrue(engineSession.factRegistry().contains(factKeyBase));
    assertTrue(engineSession.factRegistry().contains(factKeyTail));
    assertEquals(
        "Recovery Base Updated", engineSession.factRegistry().get(factKeyBase).data().get("name"));
    assertEquals(logRowsBeforeRecovery, dsl.fetchCount(table("changeset_log")));
  }

  @Test
  void recoveryWithoutCheckpointReturnsFreshSessionResult() {
    resetState();
    RecoveryOrchestrator.RecoveryResult result = recoveryOrchestrator.recover(engineSession);
    assertFalse(result.recoveredFromCheckpoint());
    assertEquals(0, result.checkpointSequenceNum());
    assertEquals(0, result.replayedChangesets());
    assertEquals(0, result.convergenceRulesFired());
  }

  @Test
  void recoveryFailsFastWhenCheckpointPayloadIsCorrupted() {
    resetState();
    String factKeyBase = "customer:REC-CORRUPT-" + UUID.randomUUID();
    changesetProcessor.process(changeset(UUID.randomUUID(), factKeyBase, "Corrupt Base"));
    checkpointService.createCheckpoint("test-corrupt-checkpoint");

    dsl.update(table("checkpoints"))
        .set(field("fact_blob", byte[].class), new byte[] {1, 2, 3})
        .execute();

    engineSession.restoreFromSnapshot(List.of(), 0L, true);

    CheckpointException failure =
        assertThrows(CheckpointException.class, () -> recoveryOrchestrator.recover(engineSession));

    assertTrue(failure.getMessage().contains("phase=load_checkpoint"));
    assertEquals("recovery_bootstrap", failure.details().get("operation"));
    assertEquals("load_checkpoint", failure.details().get("phase"));
  }

  @Test
  void recoveryFailsFastWhenCheckpointContractMismatches() {
    resetState();
    String factKeyBase = "customer:REC-MISMATCH-" + UUID.randomUUID();
    changesetProcessor.process(changeset(UUID.randomUUID(), factKeyBase, "Mismatch Base"));
    checkpointService.createCheckpoint("test-mismatch-checkpoint");

    dsl.update(table("checkpoints")).set(field("fact_count", Integer.class), 999).execute();

    engineSession.restoreFromSnapshot(List.of(), 0L, true);

    CheckpointException failure =
        assertThrows(CheckpointException.class, () -> recoveryOrchestrator.recover(engineSession));

    assertTrue(failure.getMessage().contains("fact_count mismatch"));
    assertEquals("load_checkpoint", failure.details().get("phase"));
  }

  @Test
  void replayTailFailureRestoresCheckpointStateBeforeAbort() {
    resetState();
    String factKeyBase = "customer:REC-BASE-" + UUID.randomUUID();
    String factKeyTail = "customer:REC-BADTAIL-" + UUID.randomUUID();

    changesetProcessor.process(changeset(UUID.randomUUID(), factKeyBase, "Checkpoint Base"));
    checkpointService.createCheckpoint("test-replay-failure");

    ChangesetResponse firstTail =
        changesetProcessor.process(changeset(UUID.randomUUID(), factKeyBase, "Tail Update"));
    ChangesetResponse secondTail =
        changesetProcessor.process(changeset(UUID.randomUUID(), factKeyTail, "Tail Insert"));

    assertNotNull(firstTail.sequenceNum);
    assertNotNull(secondTail.sequenceNum);

    dsl.update(table("changeset_log"))
        .set(field("payload", JSONB.class), JSONB.valueOf("{}"))
        .where(field("sequence_num", Long.class).eq(secondTail.sequenceNum))
        .execute();

    int logRowsBeforeRecovery = dsl.fetchCount(table("changeset_log"));
    engineSession.restoreFromSnapshot(List.of(), 0L, true);

    CheckpointException failure =
        assertThrows(CheckpointException.class, () -> recoveryOrchestrator.recover(engineSession));

    assertTrue(failure.getMessage().contains("phase=replay_tail"));
    assertEquals("replay_tail", failure.details().get("phase"));
    assertEquals(logRowsBeforeRecovery, dsl.fetchCount(table("changeset_log")));
    assertTrue(engineSession.factRegistry().contains(factKeyBase));
    assertFalse(engineSession.factRegistry().contains(factKeyTail));
    assertEquals(
        "Checkpoint Base", engineSession.factRegistry().get(factKeyBase).data().get("name"));
  }

  private Changeset changeset(UUID changesetId, String factKey, String name) {
    String customerId = factKey.replace("customer:", "");
    return new Changeset(
        changesetId,
        List.of(
            new ChangesetEntry(
                EntryKind.FACT,
                ChangesetAction.UPSERT,
                factKey,
                "Customer",
                Map.of("customerId", customerId, "name", name, "tier", "STANDARD", "balance", 1000),
                null,
                null)),
        Map.of("source", "recovery-test"));
  }

  private void resetState() {
    dsl.deleteFrom(table("changeset_log")).execute();
    dsl.deleteFrom(table("checkpoints")).execute();
    engineSession.restoreFromSnapshot(List.of(), 0L, true);
  }
}
