package com.sky.synome.ops.stream;

import java.time.Instant;

public record RecoveryStreamEvent(
    String eventType,
    Long sequence,
    String lifecycleState,
    String phase,
    Instant timestamp,
    String message) {}
