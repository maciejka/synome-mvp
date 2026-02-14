package com.sky.synome.ops.stream;

import java.time.Instant;
import java.util.UUID;

public record ChangesetStreamEvent(
    String eventType,
    Long sequenceNum,
    UUID changesetId,
    Instant appliedAt,
    Integer rulesFired,
    Integer durationMs) {}
