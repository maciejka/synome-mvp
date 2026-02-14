package com.sky.synome.provenance;

import com.sky.synome.config.EngineConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ProvenancePersistenceService {

  private static final Logger LOG = Logger.getLogger(ProvenancePersistenceService.class);

  private final EngineConfig config;
  private final ProvenanceRepository repository;

  private BlockingQueue<ProvenanceCapture> queue;
  private ScheduledExecutorService flushExecutor;

  @Inject
  public ProvenancePersistenceService(EngineConfig config, ProvenanceRepository repository) {
    this.config = config;
    this.repository = repository;
  }

  @PostConstruct
  void init() {
    this.queue = new LinkedBlockingQueue<>(Math.max(1, config.provenanceQueueCapacity()));
    this.flushExecutor = Executors.newSingleThreadScheduledExecutor();

    long flushIntervalMs = Math.max(100L, config.provenanceFlushInterval().toMillis());
    flushExecutor.scheduleWithFixedDelay(
        this::flushSafely, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
  }

  @PreDestroy
  void shutdown() {
    if (flushExecutor != null) {
      flushExecutor.shutdown();
      try {
        flushExecutor.awaitTermination(5, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
    flushSafely();
  }

  public void enqueue(ProvenanceCapture capture) {
    if (capture == null || capture.isEmpty()) {
      return;
    }

    if (!queue.offer(capture)) {
      LOG.errorf(
          "Provenance queue overflow. capture dropped for changesetId=%s", capture.changesetId());
    }
  }

  void flushSafely() {
    if (queue == null) {
      return;
    }

    List<ProvenanceCapture> batch = drainBatch(Math.max(1, config.provenanceBatchSize()));
    if (batch.isEmpty()) {
      return;
    }

    try {
      persistBatch(batch);
      return;
    } catch (RuntimeException firstFailure) {
      long backoffMs = Math.max(10L, config.provenanceRetryBackoff().toMillis());
      LOG.warnf(firstFailure, "Provenance batch flush failed, retrying in %dms", backoffMs);
      sleepQuietly(backoffMs);
    }

    try {
      persistBatch(batch);
    } catch (RuntimeException secondFailure) {
      LOG.errorf(
          secondFailure,
          "Provenance batch flush failed after retry; dropping %d captures",
          batch.size());
    }
  }

  public void flushNow() {
    flushSafely();
  }

  private List<ProvenanceCapture> drainBatch(int limit) {
    List<ProvenanceCapture> batch = new ArrayList<>(limit);
    queue.drainTo(batch, limit);
    return batch;
  }

  private void persistBatch(List<ProvenanceCapture> captures) {
    List<ProvenanceFactRecord> factUpserts = new ArrayList<>();
    List<ProvenanceRetraction> retractions = new ArrayList<>();
    List<ProvenanceModification> modifications = new ArrayList<>();

    for (ProvenanceCapture capture : captures) {
      factUpserts.addAll(capture.factUpserts());
      retractions.addAll(capture.retractions());
      modifications.addAll(capture.modifications());
    }

    repository.upsertFacts(factUpserts);
    repository.applyRetractions(retractions);
    repository.insertModifications(modifications);
  }

  private void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
