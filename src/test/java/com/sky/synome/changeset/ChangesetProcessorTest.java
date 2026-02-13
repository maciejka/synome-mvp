package com.sky.synome.changeset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import com.sky.synome.api.dto.ChangesetResponse;
import com.sky.synome.core.EngineSession;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChangesetProcessorTest {

  @Inject ChangesetProcessor processor;
  @Inject EngineSession engineSession;
  @Inject DSLContext dsl;

  @Test
  @Order(1)
  void upsertInsertsFactsAndFiresRules() {
    Changeset cs =
        new Changeset(
            UUID.randomUUID(),
            List.of(
                new ChangesetEntry(
                    EntryKind.FACT,
                    ChangesetAction.UPSERT,
                    "customer:C-100",
                    "Customer",
                    Map.of(
                        "customerId",
                        "C-100",
                        "name",
                        "Alice",
                        "tier",
                        "PREMIUM",
                        "balance",
                        50000),
                    null,
                    null),
                new ChangesetEntry(
                    EntryKind.FACT,
                    ChangesetAction.UPSERT,
                    "account:A-100",
                    "Account",
                    Map.of(
                        "accountId",
                        "A-100",
                        "customerId",
                        "C-100",
                        "type",
                        "SAVINGS",
                        "balance",
                        150000),
                    null,
                    null)),
            Map.of());

    ChangesetResponse response = processor.process(cs);

    assertEquals("APPLIED", response.status);
    assertEquals(2, response.effects.factsInserted);
    assertEquals(1, response.rulesFired);
    assertEquals(1, response.effects.derivedFactsCreated);
    assertTrue(response.sequenceNum > 0);
    assertNotNull(response.newDerivedFacts);
    assertEquals("HighValueCustomer", response.newDerivedFacts.get(0).factType());
  }

  @Test
  @Order(2)
  void reUpsertUpdatesFact() {
    Changeset cs =
        new Changeset(
            UUID.randomUUID(),
            List.of(
                new ChangesetEntry(
                    EntryKind.FACT,
                    ChangesetAction.UPSERT,
                    "customer:C-100",
                    "Customer",
                    Map.of(
                        "customerId",
                        "C-100",
                        "name",
                        "Alice Updated",
                        "tier",
                        "PREMIUM",
                        "balance",
                        60000),
                    null,
                    null)),
            Map.of());

    ChangesetResponse response = processor.process(cs);

    assertEquals("APPLIED", response.status);
    assertEquals(1, response.effects.factsUpdated);
    assertEquals(0, response.effects.factsInserted);
  }

  @Test
  @Order(3)
  void deleteRemovesFact() {
    Changeset cs =
        new Changeset(
            UUID.randomUUID(),
            List.of(
                new ChangesetEntry(
                    EntryKind.FACT,
                    ChangesetAction.DELETE,
                    "customer:C-100",
                    "Customer",
                    null,
                    null,
                    null)),
            Map.of());

    ChangesetResponse response = processor.process(cs);

    assertEquals("APPLIED", response.status);
    assertEquals(1, response.effects.factsDeleted);
    // TMS should retract HighValueCustomer since customer is gone
    assertTrue(
        response.effects.derivedFactsRetracted > 0,
        "HighValueCustomer should be retracted via TMS");
  }

  @Test
  @Order(4)
  void validationRejectsUnknownFactType() {
    Changeset cs =
        new Changeset(
            UUID.randomUUID(),
            List.of(
                new ChangesetEntry(
                    EntryKind.FACT,
                    ChangesetAction.UPSERT,
                    "foo:1",
                    "NonExistentType",
                    Map.of("x", 1),
                    null,
                    null)),
            Map.of());

    assertThrows(ChangesetValidator.ValidationException.class, () -> processor.process(cs));
  }

  @Test
  @Order(5)
  void changesetIsLoggedToDatabase() {
    Changeset cs =
        new Changeset(
            UUID.randomUUID(),
            List.of(
                new ChangesetEntry(
                    EntryKind.FACT,
                    ChangesetAction.UPSERT,
                    "customer:C-200",
                    "Customer",
                    Map.of(
                        "customerId", "C-200", "name", "Bob", "tier", "STANDARD", "balance", 1000),
                    null,
                    null)),
            Map.of());

    ChangesetResponse response = processor.process(cs);
    assertTrue(response.sequenceNum > 0, "Changeset should be logged with a sequence number");
  }

  @Test
  @Order(6)
  void duplicateSamePayloadReturnsOriginalResponse() {
    UUID changesetId = UUID.randomUUID();
    String factKey = "customer:C-300";
    Changeset changeset =
        new Changeset(
            changesetId,
            List.of(
                new ChangesetEntry(
                    EntryKind.FACT,
                    ChangesetAction.UPSERT,
                    factKey,
                    "Customer",
                    Map.of(
                        "customerId",
                        "C-300",
                        "name",
                        "Carol",
                        "tier",
                        "STANDARD",
                        "balance",
                        1200),
                    null,
                    null)),
            Map.of());

    ChangesetResponse first = processor.process(changeset);
    var handleAfterFirst = engineSession.factRegistry().get(factKey).handle();

    ChangesetResponse replay = processor.process(changeset);
    var handleAfterReplay = engineSession.factRegistry().get(factKey).handle();

    assertEquals(first.sequenceNum, replay.sequenceNum);
    assertEquals(first.rulesFired, replay.rulesFired);
    assertEquals(first.effects.factsInserted, replay.effects.factsInserted);
    assertEquals(first.effects.factsUpdated, replay.effects.factsUpdated);
    assertEquals(first.effects.derivedFactsCreated, replay.effects.derivedFactsCreated);
    assertSame(handleAfterFirst, handleAfterReplay, "Duplicate replay must not re-apply entries");
    assertEquals(1, countLogRows(changesetId));
  }

  @Test
  @Order(7)
  void duplicateDifferentPayloadIsRejectedBeforeMutation() {
    UUID changesetId = UUID.randomUUID();
    String factKey = "customer:C-301";

    Changeset original =
        new Changeset(
            changesetId,
            List.of(
                new ChangesetEntry(
                    EntryKind.FACT,
                    ChangesetAction.UPSERT,
                    factKey,
                    "Customer",
                    Map.of(
                        "customerId",
                        "C-301",
                        "name",
                        "Dora",
                        "tier",
                        "STANDARD",
                        "balance",
                        900),
                    null,
                    null)),
            Map.of());
    processor.process(original);
    var handleBefore = engineSession.factRegistry().get(factKey).handle();

    Changeset changedPayload =
        new Changeset(
            changesetId,
            List.of(
                new ChangesetEntry(
                    EntryKind.FACT,
                    ChangesetAction.UPSERT,
                    factKey,
                    "Customer",
                    Map.of(
                        "customerId",
                        "C-301",
                        "name",
                        "Dora Changed",
                        "tier",
                        "STANDARD",
                        "balance",
                        900),
                    null,
                    null)),
            Map.of());

    DuplicatePayloadMismatchException ex =
        assertThrows(
            DuplicatePayloadMismatchException.class, () -> processor.process(changedPayload));
    assertTrue(ex.getMessage().contains("different payload"));

    var entryAfter = engineSession.factRegistry().get(factKey);
    assertSame(handleBefore, entryAfter.handle(), "Mismatched duplicate must not mutate facts");
    assertEquals("Dora", entryAfter.data().get("name"));
    assertEquals(1, countLogRows(changesetId));
  }

  private int countLogRows(UUID changesetId) {
    return dsl.fetchCount(
        table("changeset_log"), field("changeset_id", UUID.class).eq(changesetId));
  }
}
