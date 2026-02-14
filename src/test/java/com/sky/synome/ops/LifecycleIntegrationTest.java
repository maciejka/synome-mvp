package com.sky.synome.ops;

import static org.hamcrest.Matchers.equalTo;

import com.sky.synome.api.ApiTestAuth;
import com.sky.synome.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class LifecycleIntegrationTest {

  private static final String CHANGESET_ID_PLACEHOLDER = "__CHANGESET_ID__";

  @Inject EngineLifecycle engineLifecycle;

  @AfterEach
  void resetLifecycleState() {
    engineLifecycle.markRunning("test_reset", "Reset lifecycle state after integration test");
  }

  @Test
  void readinessIsDownDuringShutdownState() {
    engineLifecycle.markShutdown("test", "Entering shutdown mode for readiness test");

    io.restassured.RestAssured.given()
        .when()
        .get("/q/health/ready")
        .then()
        .statusCode(503)
        .body("status", equalTo("DOWN"));
  }

  @Test
  void changesetWriteIsRejectedDuringShutdownState() {
    engineLifecycle.markShutdown("test", "Entering shutdown mode for write-gating test");

    ApiTestAuth.givenAuthorized()
        .contentType(ContentType.JSON)
        .body(sampleChangesetPayload(UUID.randomUUID()))
        .when()
        .post("/api/v1/changesets")
        .then()
        .statusCode(503)
        .body("code", equalTo("ENGINE_NOT_READY"));
  }

  private String sampleChangesetPayload(UUID changesetId) {
    return """
        {
          "id": "__CHANGESET_ID__",
          "entries": [
            {
              "kind": "FACT",
              "action": "UPSERT",
              "factKey": "customer:C-LIFECYCLE-1",
              "factType": "Customer",
              "data": {
                "customerId": "C-LIFECYCLE-1",
                "name": "Lifecycle User",
                "tier": "STANDARD",
                "balance": 350
              }
            }
          ]
        }
        """
        .replace(CHANGESET_ID_PLACEHOLDER, changesetId.toString());
  }
}
