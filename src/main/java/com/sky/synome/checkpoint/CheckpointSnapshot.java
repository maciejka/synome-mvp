package com.sky.synome.checkpoint;

import com.sky.synome.core.EngineSession;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record CheckpointSnapshot(
    UUID checkpointId,
    long sequenceNum,
    long clockMillis,
    UUID ruleVersionId,
    OffsetDateTime createdAt,
    List<EngineSession.RestorableFact> facts) {}
