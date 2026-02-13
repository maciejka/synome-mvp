package com.sky.synome.changeset;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.Map;

public record ChangesetEntry(
    EntryKind kind,
    ChangesetAction action,
    String factKey,
    String factType,
    Map<String, Object> data,
    String entryPoint,
    @JsonFormat(shape = JsonFormat.Shape.STRING) Instant timestamp) {}
