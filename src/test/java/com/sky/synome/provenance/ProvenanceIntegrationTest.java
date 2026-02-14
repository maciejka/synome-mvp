package com.sky.synome.provenance;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sky.synome.api.dto.ChangesetResponse;
import com.sky.synome.changeset.Changeset;
import com.sky.synome.changeset.ChangesetAction;
import com.sky.synome.changeset.ChangesetEntry;
import com.sky.synome.changeset.ChangesetProcessor;
import com.sky.synome.changeset.EntryKind;
import com.sky.synome.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class ProvenanceIntegrationTest {

  @Inject ChangesetProcessor changesetProcessor;

  @Inject DSLContext dsl;

  @Test
  void derivedAndEventProvenanceArePersistedWithRetractionHistory() throws Exception {
    String customerId = "C-PROV-INT-" + UUID.randomUUID().toString().substring(0, 8);
    String accountId = "A-PROV-INT-" + UUID.randomUUID().toString().substring(0, 8);

    ChangesetResponse baseResponse =
        changesetProcessor.process(
            new Changeset(
                UUID.randomUUID(),
                List.of(
                    new ChangesetEntry(
                        EntryKind.FACT,
                        ChangesetAction.UPSERT,
                        "customer:" + customerId,
                        "Customer",
                        Map.of(
                            "customerId",
                            customerId,
                            "name",
                            "Prov Integration",
                            "tier",
                            "PREMIUM",
                            "balance",
                            50000),
                        null,
                        null),
                    new ChangesetEntry(
                        EntryKind.FACT,
                        ChangesetAction.UPSERT,
                        "account:" + accountId,
                        "Account",
                        Map.of(
                            "accountId",
                            accountId,
                            "customerId",
                            customerId,
                            "type",
                            "SAVINGS",
                            "balance",
                            200000),
                        null,
                        null)),
                Map.of()));

    assertFalse(baseResponse.newDerivedFacts.isEmpty());
    String highValueFactId = baseResponse.newDerivedFacts.getFirst().factId();

    Record derivedRow = waitForFactRow(highValueFactId);
    assertEquals("HighValueCustomer", derivedRow.get(field("fact_type", String.class)));
    String[] highValueInputs = derivedRow.get(field("input_fact_ids", String[].class));
    assertNotNull(highValueInputs);
    assertTrue(List.of(highValueInputs).contains("base:customer:" + customerId));

    changesetProcessor.process(
        new Changeset(
            UUID.randomUUID(),
            List.of(
                new ChangesetEntry(
                    EntryKind.FACT,
                    ChangesetAction.DELETE,
                    "customer:" + customerId,
                    "Customer",
                    null,
                    null,
                    null)),
            Map.of()));

    Record retractedRow = waitForRetraction(highValueFactId);
    assertNotNull(retractedRow.get(field("retracted_at")));

    ChangesetResponse eventResponse =
        changesetProcessor.process(
            new Changeset(
                UUID.randomUUID(),
                List.of(
                    new ChangesetEntry(
                        EntryKind.EVENT,
                        ChangesetAction.EMIT,
                        null,
                        "Transaction",
                        Map.of(
                            "txId",
                            "TX-PROV-" + customerId,
                            "customerId",
                            customerId,
                            "amount",
                            3000.0),
                        "transactions",
                        Instant.now())),
                Map.of()));

    assertFalse(eventResponse.newDerivedFacts.isEmpty());
    String recentLargeTxId =
        eventResponse.newDerivedFacts.stream()
            .filter(summary -> "RecentLargeTransaction".equals(summary.factType()))
            .findFirst()
            .orElseThrow()
            .factId();

    Record recentLargeRow = waitForFactRow(recentLargeTxId);
    String[] recentInputs = recentLargeRow.get(field("input_fact_ids", String[].class));
    assertNotNull(recentInputs);
    assertEquals(1, recentInputs.length);

    Record eventRow = waitForFactRow(recentInputs[0]);
    assertEquals("EVENT", eventRow.get(field("insertion_type", String.class)));
    assertEquals("Transaction", eventRow.get(field("fact_type", String.class)));
  }

  private Record waitForFactRow(String factId) throws Exception {
    for (int attempt = 0; attempt < 40; attempt++) {
      Record record =
          dsl.select()
              .from(table("fact_provenance"))
              .where(field("fact_id", String.class).eq(factId))
              .fetchOne();
      if (record != null) {
        return record;
      }
      Thread.sleep(100L);
    }
    throw new AssertionError("Timed out waiting for fact_provenance row: " + factId);
  }

  private Record waitForRetraction(String factId) throws Exception {
    for (int attempt = 0; attempt < 40; attempt++) {
      Record record =
          dsl.select()
              .from(table("fact_provenance"))
              .where(field("fact_id", String.class).eq(factId))
              .and(field("retracted_at").isNotNull())
              .fetchOne();
      if (record != null) {
        return record;
      }
      Thread.sleep(100L);
    }
    throw new AssertionError("Timed out waiting for retraction row: " + factId);
  }
}
