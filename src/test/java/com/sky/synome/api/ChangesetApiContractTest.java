package com.sky.synome.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
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

  @Test
  void duplicateSamePayloadReturnsOriginalResponse() {
    UUID changesetId = UUID.randomUUID();
    String payload =
        """
        {
          "id": "%s",
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
        """
            .formatted(changesetId);

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
        """
        {
          "id": "%s",
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
        """
            .formatted(changesetId);
    String changedPayload =
        """
        {
          "id": "%s",
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
        """
            .formatted(changesetId);

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
        """
        {
          "id": "%s",
          "entries": [
            {
              "kind": "EVENT",
              "action": "DELETE",
              "factType": "Transaction"
            }
          ]
        }
        """
            .formatted(UUID.randomUUID());

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
}
