package com.sky.synome.api.dto;

import com.sky.synome.changeset.Changeset;
import com.sky.synome.changeset.ChangesetEntry;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ChangesetRequest {

    public UUID id;
    public List<ChangesetEntry> entries;
    public Map<String, Object> metadata;

    public Changeset toChangeset() {
        UUID changesetId = (id != null) ? id : UUID.randomUUID();
        return new Changeset(
                changesetId,
                entries != null ? entries : List.of(),
                metadata != null ? metadata : Map.of()
        );
    }
}
