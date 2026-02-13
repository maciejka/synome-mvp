package com.sky.synome.checkpoint;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record CheckpointRecord(
    UUID checkpointId,
    long sequenceNum,
    UUID ruleVersionId,
    long clockMillis,
    int factCount,
    String blobFormat,
    Map<String, Object> engineMetadata,
    OffsetDateTime createdAt,
    long sizeBytes) {}
