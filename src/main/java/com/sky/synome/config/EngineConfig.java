package com.sky.synome.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

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
}
