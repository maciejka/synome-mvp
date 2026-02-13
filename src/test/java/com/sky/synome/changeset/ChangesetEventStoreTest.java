package com.sky.synome.changeset;

import static org.jooq.impl.DSL.table;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sky.synome.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class ChangesetEventStoreTest {

  @Inject ChangesetEventStore changesetEventStore;

  @Inject DSLContext dsl;

  @Test
  void persistsProjectedEventsAndSupportsReplayQueries() {
    dsl.deleteFrom(table("changeset_events")).execute();

    Instant first = Instant.parse("2026-02-13T10:00:00Z");
    Instant second = Instant.parse("2026-02-13T10:05:00Z");
    UUID changesetId = UUID.randomUUID();

    changesetEventStore.persistProjectedEvents(
        changesetId,
        42L,
        List.of(
            new ChangesetEntry(
                EntryKind.EVENT,
                ChangesetAction.EMIT,
                null,
                "Transaction",
                Map.of("txId", "TX-1", "customerId", "C-1", "amount", 100.0),
                "transactions",
                first),
            new ChangesetEntry(
                EntryKind.FACT,
                ChangesetAction.UPSERT,
                "customer:C-1",
                "Customer",
                Map.of("customerId", "C-1", "name", "Test", "tier", "STANDARD", "balance", 1),
                null,
                null),
            new ChangesetEntry(
                EntryKind.EVENT,
                ChangesetAction.EMIT,
                null,
                "Transaction",
                Map.of("txId", "TX-2", "customerId", "C-2", "amount", 200.0),
                "transactions",
                second)));

    assertEquals(2, dsl.fetchCount(table("changeset_events")));

    List<ChangesetEventStore.ProjectedEvent> replayable =
        changesetEventStore.listReplayable(first.plusSeconds(1), Set.of("transactions"), 0, 100);
    assertEquals(1, replayable.size());
    assertEquals("TX-2", replayable.getFirst().payload().get("txId"));

    List<ChangesetEventStore.ProjectedEvent> byChangeset =
        changesetEventStore.listEvents(
            "transactions", first, second.plusSeconds(1), 100, 0, changesetId);
    assertEquals(2, byChangeset.size());
  }
}
