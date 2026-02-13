package com.sky.synome.changeset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ChangesetValidatorTest {

  @Inject ChangesetValidator validator;

  @Test
  void validFactUpsertPasses() {
    Changeset changeset =
        new Changeset(
            UUID.randomUUID(),
            List.of(
                new ChangesetEntry(
                    EntryKind.FACT,
                    ChangesetAction.UPSERT,
                    "customer:C-700",
                    "Customer",
                    Map.of(
                        "customerId",
                        "C-700",
                        "name",
                        "Gina",
                        "tier",
                        "STANDARD",
                        "balance",
                        300),
                    null,
                    null)),
            Map.of());

    assertDoesNotThrow(() -> validator.validate(changeset));
  }

  @Test
  void validFactDeleteWithoutFactTypePasses() {
    Changeset changeset =
        new Changeset(
            UUID.randomUUID(),
            List.of(
                new ChangesetEntry(
                    EntryKind.FACT,
                    ChangesetAction.DELETE,
                    "customer:C-700",
                    null,
                    null,
                    null,
                    null)),
            Map.of());

    assertDoesNotThrow(() -> validator.validate(changeset));
  }

  @Test
  void validEventEmitPasses() {
    Changeset changeset =
        new Changeset(
            UUID.randomUUID(),
            List.of(
                new ChangesetEntry(
                    EntryKind.EVENT,
                    ChangesetAction.EMIT,
                    null,
                    "Transaction",
                    Map.of("txId", "TX-1", "customerId", "C-700", "amount", 100.0),
                    "transactions",
                    Instant.parse("2026-02-13T12:00:00Z"))),
            Map.of());

    assertDoesNotThrow(() -> validator.validate(changeset));
  }

  @Test
  void invalidFactEmitCombinationIsRejected() {
    Changeset changeset =
        new Changeset(
            UUID.randomUUID(),
            List.of(
                new ChangesetEntry(
                    EntryKind.FACT,
                    ChangesetAction.EMIT,
                    "customer:C-700",
                    "Customer",
                    Map.of("customerId", "C-700"),
                    "transactions",
                    Instant.parse("2026-02-13T12:00:00Z"))),
            Map.of());

    ChangesetValidator.ValidationException ex =
        assertThrows(
            ChangesetValidator.ValidationException.class, () -> validator.validate(changeset));
    assertTrue(ex.getMessage().contains("invalid kind/action combination FACT/EMIT"));
  }

  @Test
  void invalidEventDeleteCombinationIsRejected() {
    Changeset changeset =
        new Changeset(
            UUID.randomUUID(),
            List.of(
                new ChangesetEntry(
                    EntryKind.EVENT,
                    ChangesetAction.DELETE,
                    null,
                    "Transaction",
                    null,
                    "transactions",
                    Instant.parse("2026-02-13T12:00:00Z"))),
            Map.of());

    ChangesetValidator.ValidationException ex =
        assertThrows(
            ChangesetValidator.ValidationException.class, () -> validator.validate(changeset));
    assertTrue(ex.getMessage().contains("invalid kind/action combination EVENT/DELETE"));
  }

  @Test
  void eventEmitRequiresTimestampAndEntryPoint() {
    Changeset changeset =
        new Changeset(
            UUID.randomUUID(),
            List.of(
                new ChangesetEntry(
                    EntryKind.EVENT,
                    ChangesetAction.EMIT,
                    null,
                    "Transaction",
                    Map.of("txId", "TX-2", "customerId", "C-700", "amount", 120.0),
                    null,
                    null)),
            Map.of());

    ChangesetValidator.ValidationException ex =
        assertThrows(
            ChangesetValidator.ValidationException.class, () -> validator.validate(changeset));
    assertTrue(ex.getMessage().contains("entryPoint is required"));
    assertTrue(ex.getMessage().contains("timestamp is required for EVENT/EMIT"));
  }
}
