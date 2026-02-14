package com.sky.synome.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import com.sky.synome.security.ApiKeyHashingService;
import com.sky.synome.security.ApiPermission;
import com.sky.synome.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class SecurityApiContractTest {

  private static final String CHANGESET_ID_PLACEHOLDER = "__CHANGESET_ID__";

  @jakarta.inject.Inject DSLContext dsl;

  @jakarta.inject.Inject ApiKeyHashingService apiKeyHashingService;

  @Test
  void missingApiKeyReturns401() {
    given()
        .when()
        .get("/api/v1/facts")
        .then()
        .statusCode(401)
        .body("code", equalTo("API_KEY_REQUIRED"));
  }

  @Test
  void inactiveApiKeyReturns401() {
    String rawKey = "inactive-" + UUID.randomUUID();
    insertApiKey(rawKey, false, null, Set.of(ApiPermission.FACT_READ));

    given()
        .header("X-Api-Key", rawKey)
        .when()
        .get("/api/v1/facts")
        .then()
        .statusCode(401)
        .body("code", equalTo("API_KEY_INACTIVE"));
  }

  @Test
  void expiredApiKeyReturns401() {
    String rawKey = "expired-" + UUID.randomUUID();
    insertApiKey(
        rawKey,
        true,
        OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
        Set.of(ApiPermission.FACT_READ));

    given()
        .header("X-Api-Key", rawKey)
        .when()
        .get("/api/v1/facts")
        .then()
        .statusCode(401)
        .body("code", equalTo("API_KEY_EXPIRED"));
  }

  @Test
  void insufficientPermissionReturns403() {
    String rawKey = "read-only-" + UUID.randomUUID();
    insertApiKey(rawKey, true, null, Set.of(ApiPermission.FACT_READ));

    given()
        .header("X-Api-Key", rawKey)
        .contentType(ContentType.JSON)
        .body(sampleChangesetPayload(UUID.randomUUID()))
        .when()
        .post("/api/v1/changesets")
        .then()
        .statusCode(403)
        .body("code", equalTo("PERMISSION_DENIED"))
        .body("details.requiredPermission", equalTo("CHANGESET_WRITE"));
  }

  @Test
  void validApiKeyPreservesExistingSuccessBehavior() {
    ApiTestAuth.givenAuthorized().when().get("/api/v1/facts").then().statusCode(200);
  }

  private void insertApiKey(
      String rawApiKey, boolean active, OffsetDateTime expiresAt, Set<ApiPermission> permissions) {
    dsl.insertInto(org.jooq.impl.DSL.table("api_keys"))
        .set(org.jooq.impl.DSL.field("key_id", UUID.class), UUID.randomUUID())
        .set(
            org.jooq.impl.DSL.field("key_hash", String.class), apiKeyHashingService.hash(rawApiKey))
        .set(org.jooq.impl.DSL.field("name", String.class), "test-key")
        .set(
            org.jooq.impl.DSL.field("permissions", String[].class),
            ApiPermission.toArray(permissions))
        .set(org.jooq.impl.DSL.field("is_active", Boolean.class), active)
        .set(org.jooq.impl.DSL.field("expires_at", OffsetDateTime.class), expiresAt)
        .execute();
  }

  private String sampleChangesetPayload(UUID changesetId) {
    return """
        {
          "id": "__CHANGESET_ID__",
          "entries": [
            {
              "kind": "FACT",
              "action": "UPSERT",
              "factKey": "customer:C-M6-1",
              "factType": "Customer",
              "data": {
                "customerId": "C-M6-1",
                "name": "Security User",
                "tier": "STANDARD",
                "balance": 150
              }
            }
          ]
        }
        """
        .replace(CHANGESET_ID_PLACEHOLDER, changesetId.toString());
  }
}
