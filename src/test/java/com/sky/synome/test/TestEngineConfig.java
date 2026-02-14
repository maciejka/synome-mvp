package com.sky.synome.test;

import com.sky.synome.config.EngineConfig;
import java.time.Duration;

public final class TestEngineConfig implements EngineConfig {

  private final String rulesPath;
  private final String defaultRulesFile;
  private final int provenanceQueueCapacity;
  private final int provenanceBatchSize;
  private final Duration provenanceFlushInterval;
  private final Duration provenanceRetryBackoff;

  public TestEngineConfig(String rulesPath, String defaultRulesFile) {
    this(rulesPath, defaultRulesFile, 2000, 100, Duration.ofMillis(500), Duration.ofMillis(200));
  }

  public TestEngineConfig(
      String rulesPath,
      String defaultRulesFile,
      int provenanceQueueCapacity,
      int provenanceBatchSize,
      Duration provenanceFlushInterval,
      Duration provenanceRetryBackoff) {
    this.rulesPath = rulesPath;
    this.defaultRulesFile = defaultRulesFile;
    this.provenanceQueueCapacity = provenanceQueueCapacity;
    this.provenanceBatchSize = provenanceBatchSize;
    this.provenanceFlushInterval = provenanceFlushInterval;
    this.provenanceRetryBackoff = provenanceRetryBackoff;
  }

  @Override
  public String rulesPath() {
    return rulesPath;
  }

  @Override
  public String defaultRulesFile() {
    return defaultRulesFile;
  }

  @Override
  public long lockTimeoutMs() {
    return 5000;
  }

  @Override
  public boolean checkpointEnabled() {
    return true;
  }

  @Override
  public boolean checkpointSchedulerEnabled() {
    return true;
  }

  @Override
  public String checkpointInterval() {
    return "10m";
  }

  @Override
  public int checkpointRetainCount() {
    return 20;
  }

  @Override
  public boolean recoveryEnabled() {
    return true;
  }

  @Override
  public boolean eventReplayEnabled() {
    return true;
  }

  @Override
  public Duration eventReplayWindow() {
    return Duration.ofMinutes(30);
  }

  @Override
  public String eventEntrypoints() {
    return "transactions";
  }

  @Override
  public int provenanceQueueCapacity() {
    return provenanceQueueCapacity;
  }

  @Override
  public int provenanceBatchSize() {
    return provenanceBatchSize;
  }

  @Override
  public Duration provenanceFlushInterval() {
    return provenanceFlushInterval;
  }

  @Override
  public Duration provenanceRetryBackoff() {
    return provenanceRetryBackoff;
  }
}
