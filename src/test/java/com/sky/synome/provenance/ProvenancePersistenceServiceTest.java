package com.sky.synome.provenance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sky.synome.config.EngineConfig;
import com.sky.synome.test.TestEngineConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProvenancePersistenceServiceTest {

  @Test
  void queueOverflowDropsNewestCaptureAndFlushPersistsBatch() {
    FakeProvenanceRepository repository = new FakeProvenanceRepository();
    ProvenancePersistenceService service =
        new ProvenancePersistenceService(
            config(1, 10, Duration.ofHours(1), Duration.ofMillis(10)), repository);

    service.init();
    try {
      service.enqueue(capture("base:a"));
      service.enqueue(capture("base:b"));
      service.flushSafely();

      assertEquals(1, repository.upsertedFacts);
      assertEquals(0, repository.retractions);
      assertEquals(0, repository.modifications);
    } finally {
      service.shutdown();
    }
  }

  private static ProvenanceCapture capture(String factId) {
    return new ProvenanceCapture(
        UUID.randomUUID(),
        List.of(
            new ProvenanceFactRecord(
                factId,
                "Customer",
                factId,
                null,
                ProvenanceInsertionType.BASE_FACT,
                List.of(),
                UUID.randomUUID(),
                Instant.now(),
                null,
                null)),
        List.of(),
        List.of(),
        List.of(),
        0);
  }

  private static EngineConfig config(
      int queueCapacity, int batchSize, Duration flush, Duration backoff) {
    return new TestEngineConfig(
        "rules", "bootstrap-rules.drl", queueCapacity, batchSize, flush, backoff);
  }

  private static class FakeProvenanceRepository extends ProvenanceRepository {

    int upsertedFacts;
    int retractions;
    int modifications;

    @Override
    public void upsertFacts(List<ProvenanceFactRecord> facts) {
      upsertedFacts += facts.size();
    }

    @Override
    public void applyRetractions(List<ProvenanceRetraction> retractionsList) {
      retractions += retractionsList.size();
    }

    @Override
    public void insertModifications(List<ProvenanceModification> modificationsList) {
      modifications += modificationsList.size();
    }

    @Override
    public java.util.List<ProvenanceFactRecord> search(
        String factType,
        String factKeyPrefix,
        String producedByRule,
        Integer limit,
        Integer offset,
        Boolean activeOnly) {
      return List.of();
    }

    @Override
    public java.util.Optional<ProvenanceFactRecord> findFactById(String factId) {
      return java.util.Optional.empty();
    }

    @Override
    public java.util.List<ProvenanceModification> listModifications(String factId, int limit) {
      return List.of();
    }

    @Override
    public java.util.List<ProvenanceFactRecord> findDependents(String inputFactId) {
      return List.of();
    }

    @Override
    public java.util.List<ProvenanceFactRecord> findFactsByIds(
        java.util.Collection<String> factIds) {
      return List.of();
    }
  }
}
