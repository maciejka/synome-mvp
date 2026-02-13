package com.sky.synome.changeset;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sky.synome.core.EngineSession;
import com.sky.synome.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(LockTimeoutTestProfile.class)
class ChangesetProcessorLockTimeoutConfigTest {

  @Inject ChangesetProcessor processor;
  @Inject EngineSession engineSession;

  @Test
  void lockTimeoutRespectsConfiguredValue() throws InterruptedException {
    var lock = engineSession.sessionLock();
    CountDownLatch lockAcquired = new CountDownLatch(1);
    CountDownLatch releaseLock = new CountDownLatch(1);
    Thread lockHolder =
        new Thread(
            () -> {
              if (!lock.tryAcquire(0)) {
                return;
              }
              try {
                lockAcquired.countDown();
                if (!releaseLock.await(5, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("Timed out waiting to release lock");
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                lock.release();
              }
            });
    lockHolder.start();

    assertTrue(
        lockAcquired.await(1, TimeUnit.SECONDS), "Test setup failed to acquire session lock");

    try {
      Changeset changeset =
          new Changeset(
              UUID.randomUUID(),
              List.of(
                  new ChangesetEntry(
                      EntryKind.FACT,
                      ChangesetAction.UPSERT,
                      "customer:C-LOCK",
                      "Customer",
                      Map.of(
                          "customerId",
                          "C-LOCK",
                          "name",
                          "Locky",
                          "tier",
                          "STANDARD",
                          "balance",
                          1),
                      null,
                      null)),
              Map.of());

      long startMillis = System.currentTimeMillis();
      assertThrows(
          ChangesetProcessor.LockTimeoutException.class, () -> processor.process(changeset));
      long elapsedMillis = System.currentTimeMillis() - startMillis;

      assertTrue(
          elapsedMillis < 1_000,
          "Expected lock timeout to honor short configured timeout, elapsed=" + elapsedMillis);
    } finally {
      releaseLock.countDown();
      lockHolder.join(1_000);
    }
  }
}
