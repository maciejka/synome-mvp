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
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
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
    assertEquals(0, result.replayedEvents());
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
    assertEquals(0, result.replayedEvents());
    assertEquals(0, result.convergenceRulesFired());
  }

  @Test
  void recoveryReplaysEventTailAfterCheckpointBoundary() {
    resetState();
    Instant eventTime = Instant.parse("2026-02-13T10:05:00Z");

    changesetProcessor.process(
        changeset(UUID.randomUUID(), "customer:REC-EVENT", "Recovery Event Customer"));
    checkpointService.createCheckpoint("test-event-tail");
    changesetProcessor.process(eventChangeset(UUID.randomUUID(), "TX-REC-TAIL", eventTime));

    engineSession.restoreFromSnapshot(List.of(), 0L, true);
    RecoveryOrchestrator.RecoveryResult result = recoveryOrchestrator.recover(engineSession);

    assertTrue(result.recoveredFromCheckpoint());
    assertEquals(0, result.replayedChangesets());
    assertEquals(1, result.replayedEvents());
    assertEquals(1, transactionEventCount());
    assertEquals("TX-REC-TAIL", firstTransactionTxId());
  }

  @Test
  void recoveryReplaysInWindowEventsBeforeCheckpointBoundary() {
    resetState();
    Instant eventTime = Instant.parse("2026-02-13T09:30:00Z");

    changesetProcessor.process(eventChangeset(UUID.randomUUID(), "TX-REC-PRE", eventTime));
    changesetProcessor.process(
        changeset(UUID.randomUUID(), "customer:REC-WINDOW", "Recovery Window Customer"));
    checkpointService.createCheckpoint("test-event-pre-checkpoint");

    engineSession.restoreFromSnapshot(List.of(), 0L, true);
    RecoveryOrchestrator.RecoveryResult result = recoveryOrchestrator.recover(engineSession);

    assertTrue(result.recoveredFromCheckpoint());
    assertEquals(1, result.replayedEvents());
    assertEquals(1, transactionEventCount());
    assertEquals("TX-REC-PRE", firstTransactionTxId());
  }

  @Test
  void recoveryExcludesEventsOutsideReplayWindow() {
    resetState();
    Instant oldEvent = Instant.parse("2026-02-13T08:00:00Z");
    Instant recentEvent = Instant.parse("2026-02-13T08:45:00Z");

    changesetProcessor.process(eventChangeset(UUID.randomUUID(), "TX-REC-OLD", oldEvent));
    changesetProcessor.process(eventChangeset(UUID.randomUUID(), "TX-REC-RECENT", recentEvent));
    checkpointService.createCheckpoint("test-event-window");

    engineSession.restoreFromSnapshot(List.of(), 0L, true);
    RecoveryOrchestrator.RecoveryResult result = recoveryOrchestrator.recover(engineSession);

    assertTrue(result.recoveredFromCheckpoint());
    assertEquals(1, result.replayedEvents());
    assertEquals(1, transactionEventCount());
    assertEquals("TX-REC-RECENT", firstTransactionTxId());
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

    assertTrue(failure.getMessage().contains("phase=replay_tail_facts"));
    assertEquals("replay_tail_facts", failure.details().get("phase"));
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

  private Changeset eventChangeset(UUID changesetId, String txId, Instant timestamp) {
    return new Changeset(
        changesetId,
        List.of(
            new ChangesetEntry(
                EntryKind.EVENT,
                ChangesetAction.EMIT,
                null,
                "Transaction",
                Map.of("txId", txId, "customerId", "C-REC", "amount", 2000.0),
                "transactions",
                timestamp)),
        Map.of("source", "recovery-event-test"));
  }

  private int transactionEventCount() {
    return engineSession.kieSession().getEntryPoint("transactions").getObjects().size();
  }

  private String firstTransactionTxId() {
    Object event =
        engineSession.kieSession().getEntryPoint("transactions").getObjects().iterator().next();
    try {
      return (String) event.getClass().getMethod("getTxId").invoke(event);
    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
      throw new IllegalStateException("Could not read txId from Transaction event", e);
    }
  }

  private void resetState() {
    dsl.deleteFrom(table("changeset_events")).execute();
    dsl.deleteFrom(table("changeset_log")).execute();
    dsl.deleteFrom(table("checkpoints")).execute();
    engineSession.restoreFromSnapshot(List.of(), 0L, true);
  }
}
