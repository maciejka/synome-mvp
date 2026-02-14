package com.sky.synome.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.sky.synome.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class ProvenanceApiContractTest {

  private static final String CHANGESET_ID_PLACEHOLDER = "__CHANGESET_ID__";

  @Test
  void provenanceEndpointsReturnFactExplainImpactAndSearchContracts() throws InterruptedException {
    UUID changesetId = UUID.randomUUID();
    String payload =
        withChangesetId(
            """
        {
          "id": "__CHANGESET_ID__",
          "entries": [
            {
              "kind": "FACT",
              "action": "UPSERT",
              "factKey": "customer:C-PROV-API-1",
              "factType": "Customer",
              "data": {
                "customerId": "C-PROV-API-1",
                "name": "Provenance API User",
                "tier": "PREMIUM",
                "balance": 50000
              }
            },
            {
              "kind": "FACT",
              "action": "UPSERT",
              "factKey": "account:A-PROV-API-1",
              "factType": "Account",
              "data": {
                "accountId": "A-PROV-API-1",
                "customerId": "C-PROV-API-1",
                "type": "SAVINGS",
                "balance": 200000
              }
            }
          ]
        }
        """,
            changesetId);

    String derivedFactId =
        given()
            .contentType(ContentType.JSON)
            .body(payload)
            .when()
            .post("/api/v1/changesets")
            .then()
            .statusCode(201)
            .body("newDerivedFacts.size()", greaterThan(0))
            .extract()
            .path("newDerivedFacts[0].factId");
    assertNotNull(derivedFactId);

    waitForProvenanceFact(derivedFactId);

    given()
        .when()
        .get("/api/v1/provenance/facts/{factId}", derivedFactId)
        .then()
        .statusCode(200)
        .body("factId", equalTo(derivedFactId))
        .body("factType", equalTo("HighValueCustomer"))
        .body("producedByRule", notNullValue())
        .body("inputFactIds.size()", greaterThan(0));

    given()
        .queryParam("maxDepth", 5)
        .when()
        .get("/api/v1/provenance/facts/{factId}/explain", derivedFactId)
        .then()
        .statusCode(200)
        .body("rootFactId", equalTo(derivedFactId))
        .body("explanation.dependsOn.size()", greaterThan(0));

    given()
        .queryParam("maxDepth", 5)
        .when()
        .get("/api/v1/provenance/facts/{factId}/impact", "base:customer:C-PROV-API-1")
        .then()
        .statusCode(200)
        .body("rootFactId", equalTo("base:customer:C-PROV-API-1"))
        .body("impactedFactIds", hasItem(derivedFactId));

    given()
        .queryParam("factType", "HighValueCustomer")
        .queryParam("limit", 20)
        .when()
        .get("/api/v1/provenance/search")
        .then()
        .statusCode(200)
        .body("factId", hasItem(derivedFactId));
  }

  @Test
  void provenanceFactEndpointReturns404WhenMissing() {
    given()
        .when()
        .get("/api/v1/provenance/facts/{factId}", "derived:missing")
        .then()
        .statusCode(404);
  }

  private static String withChangesetId(String payload, UUID changesetId) {
    return payload.replace(CHANGESET_ID_PLACEHOLDER, changesetId.toString());
  }

  private static void waitForProvenanceFact(String factId) throws InterruptedException {
    for (int attempt = 0; attempt < 30; attempt++) {
      Response response = given().when().get("/api/v1/provenance/facts/{factId}", factId);
      if (response.statusCode() == 200) {
        return;
      }
      Thread.sleep(100L);
    }
    throw new AssertionError("Timed out waiting for provenance fact: " + factId);
  }
}
