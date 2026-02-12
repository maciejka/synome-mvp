package com.sky.synome.changeset;

import com.sky.synome.api.dto.ChangesetResponse;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChangesetProcessorTest {

    @Inject
    ChangesetProcessor processor;

    @Test
    @Order(1)
    void upsertInsertsFactsAndFiresRules() {
        Changeset cs = new Changeset(
                UUID.randomUUID(),
                List.of(
                        new ChangesetEntry(EntryKind.FACT, ChangesetAction.UPSERT,
                                "customer:C-100", "Customer",
                                Map.of("customerId", "C-100", "name", "Alice", "tier", "PREMIUM", "balance", 50000),
                                null, null),
                        new ChangesetEntry(EntryKind.FACT, ChangesetAction.UPSERT,
                                "account:A-100", "Account",
                                Map.of("accountId", "A-100", "customerId", "C-100", "type", "SAVINGS", "balance", 150000),
                                null, null)
                ),
                Map.of()
        );

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
        Changeset cs = new Changeset(
                UUID.randomUUID(),
                List.of(
                        new ChangesetEntry(EntryKind.FACT, ChangesetAction.UPSERT,
                                "customer:C-100", "Customer",
                                Map.of("customerId", "C-100", "name", "Alice Updated", "tier", "PREMIUM", "balance", 60000),
                                null, null)
                ),
                Map.of()
        );

        ChangesetResponse response = processor.process(cs);

        assertEquals("APPLIED", response.status);
        assertEquals(1, response.effects.factsUpdated);
        assertEquals(0, response.effects.factsInserted);
    }

    @Test
    @Order(3)
    void deleteRemovesFact() {
        Changeset cs = new Changeset(
                UUID.randomUUID(),
                List.of(
                        new ChangesetEntry(EntryKind.FACT, ChangesetAction.DELETE,
                                "customer:C-100", "Customer",
                                null, null, null)
                ),
                Map.of()
        );

        ChangesetResponse response = processor.process(cs);

        assertEquals("APPLIED", response.status);
        assertEquals(1, response.effects.factsDeleted);
        // TMS should retract HighValueCustomer since customer is gone
        assertTrue(response.effects.derivedFactsRetracted > 0,
                "HighValueCustomer should be retracted via TMS");
    }

    @Test
    @Order(4)
    void validationRejectsUnknownFactType() {
        Changeset cs = new Changeset(
                UUID.randomUUID(),
                List.of(
                        new ChangesetEntry(EntryKind.FACT, ChangesetAction.UPSERT,
                                "foo:1", "NonExistentType",
                                Map.of("x", 1),
                                null, null)
                ),
                Map.of()
        );

        assertThrows(ChangesetValidator.ValidationException.class,
                () -> processor.process(cs));
    }

    @Test
    @Order(5)
    void changesetIsLoggedToDatabase() {
        Changeset cs = new Changeset(
                UUID.randomUUID(),
                List.of(
                        new ChangesetEntry(EntryKind.FACT, ChangesetAction.UPSERT,
                                "customer:C-200", "Customer",
                                Map.of("customerId", "C-200", "name", "Bob", "tier", "STANDARD", "balance", 1000),
                                null, null)
                ),
                Map.of()
        );

        ChangesetResponse response = processor.process(cs);
        assertTrue(response.sequenceNum > 0, "Changeset should be logged with a sequence number");
    }
}
