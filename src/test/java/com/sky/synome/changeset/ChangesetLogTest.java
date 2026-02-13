package com.sky.synome.changeset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sky.synome.api.dto.ChangesetResponse;
import com.sky.synome.api.dto.EffectsSummary;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ChangesetLogTest {

  @Inject ChangesetLog changesetLog;

  @Test
  void appendWithResponsePersistsReplayPayloadAndLookupByChangesetId() {
    UUID changesetId = UUID.randomUUID();
    Changeset changeset = sampleChangeset(changesetId, "customer:C-300", "Carol");
    ChangesetResponse response = sampleResponse(changesetId, 2, 18L);

    long sequenceNum = changesetLog.append(changeset, response);
    var logged = changesetLog.findByChangesetId(changesetId);

    assertTrue(logged.isPresent());
    assertEquals(changesetId, logged.get().changesetId());
    assertEquals(sequenceNum, logged.get().sequenceNum());
    assertNotNull(logged.get().checksum());
    assertNotNull(logged.get().responsePayload());
    assertEquals(sequenceNum, logged.get().responsePayload().sequenceNum);
    assertEquals(changesetId, logged.get().responsePayload().changesetId);
    assertEquals("APPLIED", logged.get().responsePayload().status);
  }

  @Test
  void findReplayReturnsChecksumMatchAndStoredResponseForSamePayload() {
    UUID changesetId = UUID.randomUUID();
    Changeset changeset = sampleChangeset(changesetId, "customer:C-301", "Dora");
    ChangesetResponse response = sampleResponse(changesetId, 1, 11L);

    long sequenceNum = changesetLog.append(changeset, response);
    var replay = changesetLog.findReplay(changeset);

    assertTrue(replay.isPresent());
    assertTrue(replay.get().checksumMatches());
    assertNotNull(replay.get().responsePayload());
    assertEquals(sequenceNum, replay.get().responsePayload().sequenceNum);
    assertEquals(changesetId, replay.get().responsePayload().changesetId);
  }

  @Test
  void findReplayReturnsChecksumMismatchWhenPayloadDiffersForSameChangesetId() {
    UUID changesetId = UUID.randomUUID();
    Changeset original = sampleChangeset(changesetId, "customer:C-302", "Eve");
    Changeset altered = sampleChangeset(changesetId, "customer:C-302", "Eve Changed");

    changesetLog.append(original, sampleResponse(changesetId, 0, 7L));
    var replay = changesetLog.findReplay(altered);

    assertTrue(replay.isPresent());
    assertFalse(replay.get().checksumMatches());
    assertNotNull(replay.get().responsePayload());
  }

  private Changeset sampleChangeset(UUID id, String factKey, String name) {
    return new Changeset(
        id,
        List.of(
            new ChangesetEntry(
                EntryKind.FACT,
                ChangesetAction.UPSERT,
                factKey,
                "Customer",
                Map.of(
                    "customerId", factKey.replace("customer:", ""),
                    "name", name,
                    "tier", "STANDARD",
                    "balance", 1500),
                null,
                null)),
        Map.of("source", "changeset-log-test"));
  }

  private ChangesetResponse sampleResponse(UUID changesetId, int rulesFired, long durationMs) {
    ChangesetResponse response = new ChangesetResponse();
    response.changesetId = changesetId;
    response.status = "APPLIED";
    response.rulesFired = rulesFired;
    response.durationMs = durationMs;
    response.effects = new EffectsSummary();
    response.newDerivedFacts = List.of();
    return response;
  }
}
