package com.sky.synome.security;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;

@ApplicationScoped
public class ApiKeyService {

  private static final Table<Record> API_KEYS_TABLE = table("api_keys");
  private static final Field<UUID> KEY_ID_FIELD = field("key_id", UUID.class);
  private static final Field<String> KEY_HASH_FIELD = field("key_hash", String.class);
  private static final Field<String> NAME_FIELD = field("name", String.class);
  private static final Field<String[]> PERMISSIONS_FIELD = field("permissions", String[].class);
  private static final Field<OffsetDateTime> EXPIRES_AT_FIELD =
      field("expires_at", OffsetDateTime.class);
  private static final Field<Boolean> IS_ACTIVE_FIELD = field("is_active", Boolean.class);
  private static final Field<OffsetDateTime> LAST_USED_AT_FIELD =
      field("last_used_at", OffsetDateTime.class);

  @Inject DSLContext dsl;

  @Inject ApiKeyHashingService apiKeyHashingService;

  public AuthenticationResult authenticate(String rawApiKey) {
    if (rawApiKey == null || rawApiKey.isBlank()) {
      return AuthenticationResult.failure(AuthenticationFailureReason.MISSING_KEY);
    }

    String hash = apiKeyHashingService.hash(rawApiKey);
    Record record =
        dsl.select(
                KEY_ID_FIELD,
                KEY_HASH_FIELD,
                NAME_FIELD,
                PERMISSIONS_FIELD,
                EXPIRES_AT_FIELD,
                IS_ACTIVE_FIELD)
            .from(API_KEYS_TABLE)
            .where(KEY_HASH_FIELD.eq(hash))
            .fetchOne();

    if (record == null) {
      return AuthenticationResult.failure(AuthenticationFailureReason.INVALID_KEY);
    }

    if (!Boolean.TRUE.equals(record.get(IS_ACTIVE_FIELD))) {
      return AuthenticationResult.failure(AuthenticationFailureReason.INACTIVE_KEY);
    }

    OffsetDateTime expiresAt = record.get(EXPIRES_AT_FIELD);
    if (expiresAt != null && expiresAt.isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
      return AuthenticationResult.failure(AuthenticationFailureReason.EXPIRED_KEY);
    }

    AuthenticatedApiKey authenticatedApiKey =
        new AuthenticatedApiKey(
            record.get(KEY_ID_FIELD),
            record.get(NAME_FIELD),
            ApiPermission.parseArray(record.get(PERMISSIONS_FIELD)));
    updateLastUsed(authenticatedApiKey.keyId());
    return AuthenticationResult.success(authenticatedApiKey);
  }

  public void ensureBootstrapKey(String rawApiKey, String keyName, Set<ApiPermission> permissions) {
    if (rawApiKey == null || rawApiKey.isBlank()) {
      return;
    }

    String hash = apiKeyHashingService.hash(rawApiKey);
    String[] permissionValues = ApiPermission.toArray(permissions);
    Record existing =
        dsl.select(KEY_ID_FIELD).from(API_KEYS_TABLE).where(KEY_HASH_FIELD.eq(hash)).fetchOne();

    if (existing == null) {
      dsl.insertInto(API_KEYS_TABLE)
          .set(KEY_ID_FIELD, UUID.randomUUID())
          .set(KEY_HASH_FIELD, hash)
          .set(NAME_FIELD, keyName)
          .set(PERMISSIONS_FIELD, permissionValues)
          .set(IS_ACTIVE_FIELD, true)
          .set(EXPIRES_AT_FIELD, (OffsetDateTime) null)
          .execute();
      return;
    }

    dsl.update(API_KEYS_TABLE)
        .set(NAME_FIELD, keyName)
        .set(PERMISSIONS_FIELD, permissionValues)
        .set(IS_ACTIVE_FIELD, true)
        .where(KEY_HASH_FIELD.eq(hash))
        .execute();
  }

  private void updateLastUsed(UUID keyId) {
    dsl.update(API_KEYS_TABLE)
        .set(LAST_USED_AT_FIELD, OffsetDateTime.now(ZoneOffset.UTC))
        .where(KEY_ID_FIELD.eq(keyId))
        .execute();
  }

  public enum AuthenticationFailureReason {
    MISSING_KEY,
    INVALID_KEY,
    INACTIVE_KEY,
    EXPIRED_KEY
  }

  public record AuthenticationResult(
      AuthenticatedApiKey authenticatedApiKey, AuthenticationFailureReason failureReason) {

    public static AuthenticationResult success(AuthenticatedApiKey authenticatedApiKey) {
      return new AuthenticationResult(authenticatedApiKey, null);
    }

    public static AuthenticationResult failure(AuthenticationFailureReason failureReason) {
      return new AuthenticationResult(null, failureReason);
    }

    public boolean authenticated() {
      return authenticatedApiKey != null;
    }
  }
}
