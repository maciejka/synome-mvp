package com.sky.synome.changeset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jooq.DSLContext;
import org.jooq.JSONB;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

@ApplicationScoped
public class ChangesetLog {

    @Inject
    DSLContext dsl;

    @Inject
    ObjectMapper objectMapper;

    public long append(Changeset changeset, int rulesFired, long durationMs) {
        String payloadJson = serializePayload(changeset);
        String checksum = sha256(payloadJson);

        return dsl.insertInto(table("changeset_log"))
                .set(field("changeset_id"), changeset.id())
                .set(field("payload"), JSONB.valueOf(payloadJson))
                .set(field("checksum"), checksum)
                .set(field("applied_at"), OffsetDateTime.now())
                .set(field("engine_clock_at"), 0L)
                .set(field("rules_fired"), rulesFired)
                .set(field("duration_ms"), (int) durationMs)
                .returning(field("sequence_num"))
                .fetchOne()
                .get(field("sequence_num", Long.class));
    }

    private String serializePayload(Changeset changeset) {
        try {
            return objectMapper.writeValueAsString(changeset);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize changeset", e);
        }
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
