package com.sky.synome.changeset;

import java.util.Map;

public record ChangesetEntry(
        EntryKind kind,
        ChangesetAction action,
        String factKey,
        String factType,
        Map<String, Object> data,
        String entryPoint,
        Long timestamp
) {
}
