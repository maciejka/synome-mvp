package com.sky.synome.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import com.sky.synome.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class CheckpointApiContractTest {

  @Test
  void createCheckpointReturnsCreatedEnvelope() {
    given()
        .when()
        .post("/api/v1/checkpoints")
        .then()
        .statusCode(201)
        .body("status", equalTo("CREATED"))
        .body("checkpointId", notNullValue())
        .body("sequenceNum", greaterThan(-1))
        .body("clockMillis", greaterThan(-1))
        .body("factCount", greaterThan(-1))
        .body("blobFormat", equalTo("msgpack+lz4"))
        .body("sizeBytes", greaterThan(0))
        .body("engineMetadata", hasKey("schemaVersion"));
  }

  @Test
  void listLatestAndByIdAreConsistent() {
    String checkpointId =
        given()
            .when()
            .post("/api/v1/checkpoints")
            .then()
            .statusCode(201)
            .extract()
            .path("checkpointId");

    given()
        .queryParam("limit", 10)
        .queryParam("offset", 0)
        .when()
        .get("/api/v1/checkpoints")
        .then()
        .statusCode(200)
        .body("$", hasSize(greaterThan(0)))
        .body("[0]", hasKey("checkpointId"))
        .body("[0]", hasKey("sequenceNum"))
        .body("[0]", hasKey("factCount"));

    given()
        .when()
        .get("/api/v1/checkpoints/latest")
        .then()
        .statusCode(200)
        .body("checkpointId", notNullValue())
        .body("engineMetadata", hasKey("schemaVersion"));

    given()
        .when()
        .get("/api/v1/checkpoints/{id}", checkpointId)
        .then()
        .statusCode(200)
        .body("checkpointId", equalTo(checkpointId))
        .body("blobFormat", equalTo("msgpack+lz4"));
  }

  @Test
  void byIdReturns404ForMissingCheckpoint() {
    given()
        .when()
        .get("/api/v1/checkpoints/{id}", UUID.randomUUID())
        .then()
        .statusCode(404)
        .body("code", equalTo("CHECKPOINT_NOT_FOUND"));
  }
}
