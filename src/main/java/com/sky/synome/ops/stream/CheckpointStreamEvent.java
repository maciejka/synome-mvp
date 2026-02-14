package com.sky.synome.ops.stream;

import java.time.Instant;
import java.util.UUID;

public record CheckpointStreamEvent(
    String eventType,
    UUID checkpointId,
    Long sequenceNum,
    Instant createdAt,
    Integer factCount,
    Long sizeBytes) {}
