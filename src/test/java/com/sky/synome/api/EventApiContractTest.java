package com.sky.synome.api;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import com.sky.synome.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class EventApiContractTest {

  @Test
  void listEventsSupportsFilteringAndPagination() {
    UUID firstChangesetId = UUID.randomUUID();
    UUID secondChangesetId = UUID.randomUUID();
    String firstTimestamp = "2026-02-13T11:00:00Z";
    String secondTimestamp = "2026-02-13T11:01:00Z";

    ApiTestAuth.givenAuthorized()
        .contentType(ContentType.JSON)
        .body(eventPayload(firstChangesetId, "TX-API-1", firstTimestamp))
        .when()
        .post("/api/v1/changesets")
        .then()
        .statusCode(201);

    ApiTestAuth.givenAuthorized()
        .contentType(ContentType.JSON)
        .body(eventPayload(secondChangesetId, "TX-API-2", secondTimestamp))
        .when()
        .post("/api/v1/changesets")
        .then()
        .statusCode(201);

    ApiTestAuth.givenAuthorized()
        .queryParam("entryPoint", "transactions")
        .queryParam("from", "2026-02-13T11:00:30Z")
        .queryParam("to", "2026-02-13T11:01:30Z")
        .queryParam("limit", 10)
        .queryParam("offset", 0)
        .when()
        .get("/api/v1/events")
        .then()
        .statusCode(200)
        .body("$", hasSize(1))
        .body("[0].changesetId", equalTo(secondChangesetId.toString()))
        .body("[0].entryPoint", equalTo("transactions"))
        .body("[0].factType", equalTo("Transaction"))
        .body("[0].payload.txId", equalTo("TX-API-2"))
        .body("[0].eventTimestamp", equalTo(secondTimestamp));
  }

  @Test
  void listEventsByChangesetIdReturnsOnlyRequestedChangeset() {
    UUID changesetId = UUID.randomUUID();
    String timestamp = "2026-02-13T12:00:00Z";

    ApiTestAuth.givenAuthorized()
        .contentType(ContentType.JSON)
        .body(eventPayload(changesetId, "TX-API-3", timestamp))
        .when()
        .post("/api/v1/changesets")
        .then()
        .statusCode(201);

    ApiTestAuth.givenAuthorized()
        .queryParam("entryPoint", "transactions")
        .queryParam("from", "2026-02-13T11:59:00Z")
        .queryParam("to", "2026-02-13T12:01:00Z")
        .queryParam("limit", 10)
        .queryParam("offset", 0)
        .when()
        .get("/api/v1/events/{changesetId}", changesetId)
        .then()
        .statusCode(200)
        .body("$", hasSize(1))
        .body("[0].changesetId", equalTo(changesetId.toString()))
        .body("[0].payload.txId", equalTo("TX-API-3"));
  }

  private String eventPayload(UUID changesetId, String txId, String timestamp) {
    return """
        {
          "id": "__CHANGESET_ID__",
          "entries": [
            {
              "kind": "EVENT",
              "action": "EMIT",
              "factType": "Transaction",
              "entryPoint": "transactions",
              "timestamp": "__TIMESTAMP__",
              "data": {
                "txId": "__TX_ID__",
                "customerId": "C-API-EVENT",
                "amount": 2500.0
              }
            }
          ]
        }
        """
        .replace("__CHANGESET_ID__", changesetId.toString())
        .replace("__TIMESTAMP__", timestamp)
        .replace("__TX_ID__", txId);
  }
}
