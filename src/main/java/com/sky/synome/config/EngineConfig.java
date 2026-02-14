package com.sky.synome.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import java.time.Duration;

@ConfigMapping(prefix = "engine")
public interface EngineConfig {

  @WithName("rules-path")
  @WithDefault("rules")
  String rulesPath();

  @WithName("default-rules-file")
  @WithDefault("bootstrap-rules.drl")
  String defaultRulesFile();

  @WithName("lock-timeout-ms")
  @WithDefault("5000")
  long lockTimeoutMs();

  @WithName("checkpoint-enabled")
  @WithDefault("true")
  boolean checkpointEnabled();

  @WithName("checkpoint-scheduler-enabled")
  @WithDefault("true")
  boolean checkpointSchedulerEnabled();

  @WithName("checkpoint-interval")
  @WithDefault("10m")
  String checkpointInterval();

  @WithName("checkpoint-retain-count")
  @WithDefault("20")
  int checkpointRetainCount();

  @WithName("recovery-enabled")
  @WithDefault("true")
  boolean recoveryEnabled();

  @WithName("event-replay-enabled")
  @WithDefault("true")
  boolean eventReplayEnabled();

  @WithName("event-replay-window")
  @WithDefault("30m")
  Duration eventReplayWindow();

  @WithName("event-entrypoints")
  @WithDefault("transactions")
  String eventEntrypoints();

  @WithName("provenance-queue-capacity")
  @WithDefault("2000")
  int provenanceQueueCapacity();

  @WithName("provenance-batch-size")
  @WithDefault("100")
  int provenanceBatchSize();

  @WithName("provenance-flush-interval")
  @WithDefault("500ms")
  Duration provenanceFlushInterval();

  @WithName("provenance-retry-backoff")
  @WithDefault("200ms")
  Duration provenanceRetryBackoff();

  @WithName("security-enabled")
  @WithDefault("true")
  boolean securityEnabled();

  @WithName("security-bootstrap-key")
  @WithDefault("")
  String securityBootstrapKey();

  @WithName("security-bootstrap-name")
  @WithDefault("bootstrap-admin")
  String securityBootstrapName();

  @WithName("security-bootstrap-permissions")
  @WithDefault(
      "CHANGESET_WRITE,FACT_READ,CHECKPOINT_ADMIN,RULE_ADMIN,PROVENANCE_READ,OPS_STREAM_READ")
  String securityBootstrapPermissions();

  @WithName("shutdown-timeout")
  @WithDefault("15s")
  Duration shutdownTimeout();

  @WithName("ops-stream-poll-interval")
  @WithDefault("1s")
  Duration opsStreamPollInterval();

  @WithName("ops-stream-max-batch")
  @WithDefault("50")
  int opsStreamMaxBatch();
}
