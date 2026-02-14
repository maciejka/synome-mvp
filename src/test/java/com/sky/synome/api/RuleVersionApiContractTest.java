package com.sky.synome.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

import com.sky.synome.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class RuleVersionApiContractTest {

  @Test
  void validateAndUploadExposeStableContract() {
    String drl = loadBootstrapDrl();

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("versionLabel", "M5-validate", "drl", drl))
        .when()
        .post("/api/v1/rules/validate")
        .then()
        .statusCode(200)
        .body("valid", equalTo(true))
        .body("compiled", equalTo(true))
        .body("compatible", equalTo(true))
        .body("checksum", notNullValue());

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("versionLabel", "M5-upload", "drl", drl, "uploadedBy", "qa"))
        .when()
        .post("/api/v1/rules/upload")
        .then()
        .statusCode(201)
        .body("versionId", notNullValue())
        .body("versionLabel", equalTo("M5-upload"))
        .body("validation.valid", equalTo(true));
  }

  @Test
  void activateRollbackAndReadEndpointsAreConsistent() {
    String firstVersionId = upload("M5-v1", loadBootstrapDrl());
    String secondVersionId =
        upload("M5-v2", loadBootstrapDrl() + "\nrule \"Swap marker\"\nwhen\nthen\nend\n");

    applyChangeset("customer:C-RULE-1", "Before Swap");

    given()
        .when()
        .post("/api/v1/rules/{versionId}/activate", firstVersionId)
        .then()
        .statusCode(200)
        .body("status", equalTo("ACTIVATED"))
        .body("activatedVersionId", equalTo(firstVersionId))
        .body("checkpointId", notNullValue())
        .body("convergenceRulesFired", greaterThan(-1));

    applyChangeset("customer:C-RULE-2", "After First Swap");

    given()
        .when()
        .post("/api/v1/rules/{versionId}/activate", secondVersionId)
        .then()
        .statusCode(200)
        .body("status", equalTo("ACTIVATED"))
        .body("activatedVersionId", equalTo(secondVersionId));

    given()
        .when()
        .post("/api/v1/rules/{versionId}/rollback", firstVersionId)
        .then()
        .statusCode(200)
        .body("status", equalTo("ROLLED_BACK"))
        .body("activatedVersionId", equalTo(firstVersionId))
        .body("previousVersionId", equalTo(secondVersionId));

    given()
        .when()
        .get("/api/v1/rules/active")
        .then()
        .statusCode(200)
        .body("versionId", equalTo(firstVersionId))
        .body("active", equalTo(true));

    given()
        .queryParam("limit", 20)
        .queryParam("offset", 0)
        .when()
        .get("/api/v1/rules")
        .then()
        .statusCode(200)
        .body("versionId", hasItem(firstVersionId))
        .body("versionId", hasItem(secondVersionId));
  }

  @Test
  void validationFailureAndMissingVersionReturnErrorEnvelope() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("versionLabel", "invalid", "drl", "package x; rule \"broken\" when then"))
        .when()
        .post("/api/v1/rules/upload")
        .then()
        .statusCode(400)
        .body("code", equalTo("RULE_VALIDATION_ERROR"))
        .body("details.compilationErrors", notNullValue());

    given()
        .when()
        .post("/api/v1/rules/{versionId}/activate", UUID.randomUUID())
        .then()
        .statusCode(404)
        .body("code", equalTo("RULE_VERSION_NOT_FOUND"));
  }

  @Test
  void validateReportsCompatibilityMismatchForSchemaRegression() {
    String incompatibleDrl =
        """
        package com.sky.synome.rules;

        declare Customer
            customerId : String
            name       : String
            tier       : String
        end

        declare Account
            accountId  : String
            customerId : String
            type       : String
            balance    : double
        end

        declare HighValueCustomer
            customerId : String
            customerName : String
            totalBalance : double
            reason     : String
        end

        declare Transaction
            @role( event )
            @timestamp( eventTimestamp )
            txId       : String
            customerId : String
            amount     : double
            eventTimestamp : long
        end

        declare RecentLargeTransaction
            txId       : String
            customerId : String
        end

        rule "Observe transaction events"
            when
                Transaction() from entry-point "transactions"
            then
        end
        """;

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("versionLabel", "incompatible", "drl", incompatibleDrl))
        .when()
        .post("/api/v1/rules/validate")
        .then()
        .statusCode(200)
        .body("valid", equalTo(false))
        .body("compatible", equalTo(false))
        .body("compatibilityErrors[0]", notNullValue());
  }

  private String upload(String label, String drl) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of("versionLabel", label, "drl", drl, "uploadedBy", "qa"))
        .when()
        .post("/api/v1/rules/upload")
        .then()
        .statusCode(201)
        .extract()
        .path("versionId");
  }

  private void applyChangeset(String factKey, String name) {
    Map<String, Object> payload =
        Map.of(
            "id", UUID.randomUUID().toString(),
            "entries",
                List.of(
                    Map.of(
                        "kind", "FACT",
                        "action", "UPSERT",
                        "factKey", factKey,
                        "factType", "Customer",
                        "data",
                            Map.of(
                                "customerId",
                                factKey,
                                "name",
                                name,
                                "tier",
                                "PREMIUM",
                                "balance",
                                150000))));

    given()
        .contentType(ContentType.JSON)
        .body(payload)
        .when()
        .post("/api/v1/changesets")
        .then()
        .statusCode(201);
  }

  private String loadBootstrapDrl() {
    try (InputStream stream =
        Thread.currentThread()
            .getContextClassLoader()
            .getResourceAsStream("rules/bootstrap-rules.drl")) {
      if (stream == null) {
        throw new IllegalStateException("rules/bootstrap-rules.drl not found");
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to read bootstrap DRL", e);
    }
  }
}
