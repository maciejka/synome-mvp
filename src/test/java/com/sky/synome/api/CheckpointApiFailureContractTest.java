package com.sky.synome.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sky.synome.checkpoint.CheckpointLockTimeoutTestProfile;
import com.sky.synome.core.EngineSession;
import com.sky.synome.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(CheckpointLockTimeoutTestProfile.class)
class CheckpointApiFailureContractTest {

  @Inject EngineSession engineSession;

  @Test
  void createCheckpointReturnsCheckpointErrorWhenSessionLockIsBusy() throws Exception {
    CountDownLatch lockAcquired = new CountDownLatch(1);
    CountDownLatch releaseLock = new CountDownLatch(1);
    ExecutorService executor = Executors.newSingleThreadExecutor();

    Future<?> holder =
        executor.submit(
            () -> {
              var lock = engineSession.sessionLock();
              if (!lock.tryAcquire(1000L)) {
                throw new IllegalStateException("Failed to acquire lock for test setup");
              }
              lockAcquired.countDown();
              try {
                releaseLock.await(3, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                lock.release();
              }
            });

    assertTrue(lockAcquired.await(2, TimeUnit.SECONDS));
    try {
      given()
          .when()
          .post("/api/v1/checkpoints")
          .then()
          .statusCode(400)
          .body("code", equalTo("CHECKPOINT_ERROR"))
          .body("details.operation", equalTo("checkpoint_create"))
          .body("details.phase", equalTo("acquire_lock"));
    } finally {
      releaseLock.countDown();
      holder.get(2, TimeUnit.SECONDS);
      executor.shutdownNow();
    }
  }
}
