package com.sky.synome.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import com.sky.synome.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class ChangesetApiContractTest {

  private static final String CHANGESET_ID_PLACEHOLDER = "__CHANGESET_ID__";

  @Test
  void duplicateSamePayloadReturnsOriginalResponse() {
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
              "factKey": "customer:C-API-1",
              "factType": "Customer",
              "data": {
                "customerId": "C-API-1",
                "name": "Api User",
                "tier": "STANDARD",
                "balance": 1500
              }
            }
          ]
        }
        """,
            changesetId);

    Number firstSequence =
        given()
            .contentType(ContentType.JSON)
            .body(payload)
            .when()
            .post("/api/v1/changesets")
            .then()
            .statusCode(201)
            .body("sequenceNum", greaterThan(0))
            .extract()
            .path("sequenceNum");

    given()
        .contentType(ContentType.JSON)
        .body(payload)
        .when()
        .post("/api/v1/changesets")
        .then()
        .statusCode(201)
        .body("sequenceNum", equalTo(firstSequence.intValue()));
  }

  @Test
  void duplicateDifferentPayloadReturnsConflictEnvelope() {
    UUID changesetId = UUID.randomUUID();
    String firstPayload =
        withChangesetId(
            """
        {
          "id": "__CHANGESET_ID__",
          "entries": [
            {
              "kind": "FACT",
              "action": "UPSERT",
              "factKey": "customer:C-API-2",
              "factType": "Customer",
              "data": {
                "customerId": "C-API-2",
                "name": "Api User",
                "tier": "STANDARD",
                "balance": 700
              }
            }
          ]
        }
        """,
            changesetId);
    String changedPayload =
        withChangesetId(
            """
        {
          "id": "__CHANGESET_ID__",
          "entries": [
            {
              "kind": "FACT",
              "action": "UPSERT",
              "factKey": "customer:C-API-2",
              "factType": "Customer",
              "data": {
                "customerId": "C-API-2",
                "name": "Api User Changed",
                "tier": "STANDARD",
                "balance": 700
              }
            }
          ]
        }
        """,
            changesetId);

    given()
        .contentType(ContentType.JSON)
        .body(firstPayload)
        .when()
        .post("/api/v1/changesets")
        .then()
        .statusCode(201);

    given()
        .contentType(ContentType.JSON)
        .body(changedPayload)
        .when()
        .post("/api/v1/changesets")
        .then()
        .statusCode(409)
        .body("code", equalTo("DUPLICATE_CHANGESET_PAYLOAD_MISMATCH"))
        .body("message", equalTo("changeset_id already exists with different payload"))
        .body("details.changesetId", equalTo(changesetId.toString()))
        .body("timestamp", not(nullValue()))
        .body("requestId", not(nullValue()));
  }

  @Test
  void validationErrorEnvelopeShapeIsStable() {
    String invalidPayload =
        withChangesetId(
            """
        {
          "id": "__CHANGESET_ID__",
          "entries": [
            {
              "kind": "EVENT",
              "action": "DELETE",
              "factType": "Transaction"
            }
          ]
        }
        """,
            UUID.randomUUID());

    given()
        .contentType(ContentType.JSON)
        .body(invalidPayload)
        .when()
        .post("/api/v1/changesets")
        .then()
        .statusCode(400)
        .body("code", equalTo("VALIDATION_ERROR"))
        .body("details.errors", hasSize(greaterThan(0)))
        .body("timestamp", not(nullValue()))
        .body("requestId", not(nullValue()));
  }

  @Test
  void changesetReadEndpointsReturnPersistedEntries() {
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
              "factKey": "customer:C-API-LIST-1",
              "factType": "Customer",
              "data": {
                "customerId": "C-API-LIST-1",
                "name": "Read Endpoint User",
                "tier": "STANDARD",
                "balance": 42
              }
            }
          ]
        }
        """,
            changesetId);

    given()
        .contentType(ContentType.JSON)
        .body(payload)
        .when()
        .post("/api/v1/changesets")
        .then()
        .statusCode(201);

    given()
        .queryParam("limit", 50)
        .queryParam("offset", 0)
        .when()
        .get("/api/v1/changesets")
        .then()
        .statusCode(200)
        .body("$", hasSize(greaterThan(0)))
        .body("[0]", hasKey("sequenceNum"))
        .body("[0]", hasKey("changesetId"))
        .body("[0]", hasKey("appliedAt"))
        .body("[0]", hasKey("rulesFired"))
        .body("[0]", hasKey("durationMs"))
        .body("[0]", hasKey("checksum"));

    given()
        .when()
        .get("/api/v1/changesets/{id}", changesetId)
        .then()
        .statusCode(200)
        .body("changesetId", equalTo(changesetId.toString()))
        .body("payload", not(nullValue()))
        .body("checksum", not(nullValue()))
        .body("appliedAt", not(nullValue()));
  }

  @Test
  void changesetByIdReturns404WhenMissing() {
    given().when().get("/api/v1/changesets/{id}", UUID.randomUUID()).then().statusCode(404);
  }

  @Test
  void factEndpointsReturnQueryableState() {
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
              "factKey": "customer:C-API-FACT-1",
              "factType": "Customer",
              "data": {
                "customerId": "C-API-FACT-1",
                "name": "Fact Endpoint User",
                "tier": "PREMIUM",
                "balance": 200
              }
            }
          ]
        }
        """,
            changesetId);

    given()
        .contentType(ContentType.JSON)
        .body(payload)
        .when()
        .post("/api/v1/changesets")
        .then()
        .statusCode(201);

    given()
        .when()
        .get("/api/v1/facts")
        .then()
        .statusCode(200)
        .body("factKey", hasItem("customer:C-API-FACT-1"));

    given()
        .queryParam("type", "Customer")
        .when()
        .get("/api/v1/facts")
        .then()
        .statusCode(200)
        .body("factType", hasItem("Customer"));

    given()
        .when()
        .get("/api/v1/facts/{factKey}", "customer:C-API-FACT-1")
        .then()
        .statusCode(200)
        .body("factKey", equalTo("customer:C-API-FACT-1"))
        .body("factType", equalTo("Customer"))
        .body("data.customerId", equalTo("C-API-FACT-1"));

    given()
        .when()
        .get("/api/v1/facts/types")
        .then()
        .statusCode(200)
        .body("$", hasItem(endsWith("Customer")));

    given()
        .when()
        .get("/api/v1/facts/stats")
        .then()
        .statusCode(200)
        .body("totalFacts", greaterThan(0))
        .body("countByType.Customer", greaterThan(0));
  }

  @Test
  void factByKeyReturns404WhenMissing() {
    given().when().get("/api/v1/facts/{factKey}", "customer:DOES-NOT-EXIST").then().statusCode(404);
  }

  private static String withChangesetId(String payload, UUID changesetId) {
    return payload.replace(CHANGESET_ID_PLACEHOLDER, changesetId.toString());
  }
}
