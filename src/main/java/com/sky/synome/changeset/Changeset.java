package com.sky.synome.changeset;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record Changeset(
        UUID id,
        List<ChangesetEntry> entries,
        Map<String, Object> metadata
) {
}
