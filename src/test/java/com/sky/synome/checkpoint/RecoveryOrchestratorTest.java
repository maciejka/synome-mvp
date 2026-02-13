package com.sky.synome.checkpoint;

import static org.jooq.impl.DSL.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
