package com.sky.synome.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import com.sky.synome.security.ApiKeyHashingService;
import com.sky.synome.security.ApiPermission;
import com.sky.synome.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class OperationsStreamApiContractTest {

  @jakarta.inject.Inject DSLContext dsl;

  @jakarta.inject.Inject ApiKeyHashingService apiKeyHashingService;

  @Test
  void streamRequiresAuthentication() {
    given()
        .queryParam("maxEvents", 1)
        .when()
        .get("/api/v1/ops/stream/changesets")
        .then()
        .statusCode(401)
        .body("code", equalTo("API_KEY_REQUIRED"));
  }

  @Test
  void streamDeniesMissingOpsPermission() {
    String rawKey = "ops-no-perm-" + UUID.randomUUID();
    insertApiKey(rawKey, Set.of(ApiPermission.FACT_READ));

    given()
        .header("X-Api-Key", rawKey)
        .queryParam("maxEvents", 1)
        .when()
        .get("/api/v1/ops/stream/recovery")
        .then()
        .statusCode(403)
        .body("code", equalTo("PERMISSION_DENIED"));
  }

  @Test
  void streamHandshakeReturnsSsePayload() {
    String body =
        ApiTestAuth.givenAuthorized()
            .queryParam("maxEvents", 1)
            .when()
            .get("/api/v1/ops/stream/recovery")
            .then()
            .statusCode(200)
            .header("Content-Type", containsString("text/event-stream"))
            .extract()
            .asString();

    org.junit.jupiter.api.Assertions.assertTrue(body.contains("STREAM_OPENED"));
  }

  private void insertApiKey(String rawApiKey, Set<ApiPermission> permissions) {
    dsl.insertInto(org.jooq.impl.DSL.table("api_keys"))
        .set(org.jooq.impl.DSL.field("key_id", UUID.class), UUID.randomUUID())
        .set(
            org.jooq.impl.DSL.field("key_hash", String.class), apiKeyHashingService.hash(rawApiKey))
        .set(org.jooq.impl.DSL.field("name", String.class), "ops-test-key")
        .set(
            org.jooq.impl.DSL.field("permissions", String[].class),
            ApiPermission.toArray(permissions))
        .set(org.jooq.impl.DSL.field("is_active", Boolean.class), true)
        .execute();
  }
}
