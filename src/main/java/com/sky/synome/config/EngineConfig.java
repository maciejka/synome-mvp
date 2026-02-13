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
}
