package com.sky.synome.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sky.synome.config.EngineConfig;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EngineSessionUnitTest {

  @Test
  void initializesFromConfiguredDrlAndExposesDependencies() {
    EngineSession session =
        new EngineSession(config("rules", "bootstrap-rules.drl"), new RuleCompiler());
    try {
      assertNotNull(session.kieBase());
      assertNotNull(session.kieSession());
      assertNotNull(session.factRegistry());
      assertNotNull(session.sessionLock());
      assertNotNull(session.derivationTracker());
    } finally {
      session.destroy();
    }
  }

  @Test
  void rebuildFromSnapshotRecreatesFactsAndRegistryEntries() throws Exception {
    EngineSession session =
        new EngineSession(config("rules", "bootstrap-rules.drl"), new RuleCompiler());
    try {
      var customerType = session.kieBase().getFactType("com.sky.synome.rules", "Customer");
      Object customer = customerType.newInstance();
      customerType.set(customer, "customerId", "SNAP-C1");
      customerType.set(customer, "name", "Snapshot User");
      customerType.set(customer, "tier", "STANDARD");
      customerType.set(customer, "balance", 0.0);

      List<EngineSession.RestorableFact> snapshot =
          List.of(
              new EngineSession.RestorableFact(
                  "customer:SNAP-C1",
                  "Customer",
                  Map.of(
                      "customerId", "SNAP-C1",
                      "name", "Snapshot User",
                      "tier", "STANDARD",
                      "balance", 0.0),
                  customer));

      session.rebuildFromSnapshot(snapshot);

      assertEquals(1, session.factRegistry().size());
      assertTrue(session.factRegistry().contains("customer:SNAP-C1"));
      assertEquals("Customer", session.factRegistry().get("customer:SNAP-C1").factType());
    } finally {
      session.destroy();
    }
  }

  @Test
  @SuppressWarnings("removal")
  void ioFailureWhileReadingDrlIsWrapped() {
    ClassLoader original = Thread.currentThread().getContextClassLoader();
    ClassLoader brokenLoader =
        AccessController.doPrivileged(
            (PrivilegedAction<ClassLoader>)
                () ->
                    new ClassLoader(original) {
                      @Override
                      public java.io.InputStream getResourceAsStream(String name) {
                        if ("rules/io-error.drl".equals(name)) {
                          return new java.io.InputStream() {
                            @Override
                            public int read() throws java.io.IOException {
                              throw new java.io.IOException("forced read failure");
                            }
                          };
                        }
                        return super.getResourceAsStream(name);
                      }
                    });

    Thread.currentThread().setContextClassLoader(brokenLoader);
    try {
      IllegalStateException ex =
          assertThrows(
              IllegalStateException.class,
              () -> new EngineSession(config("rules", "io-error.drl"), new RuleCompiler()));
      assertTrue(ex.getMessage().contains("Failed to read DRL file"));
    } finally {
      Thread.currentThread().setContextClassLoader(original);
    }
  }

  @Test
  void missingDrlResourceFailsFast() {
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () -> new EngineSession(config("rules", "does-not-exist.drl"), new RuleCompiler()));
    assertTrue(ex.getMessage().contains("DRL file not found on classpath"));
  }

  private static EngineConfig config(String rulesPath, String rulesFile) {
    return new EngineConfig() {
      @Override
      public String rulesPath() {
        return rulesPath;
      }

      @Override
      public String defaultRulesFile() {
        return rulesFile;
      }

      @Override
      public long lockTimeoutMs() {
        return 5000;
      }

      @Override
      public boolean checkpointEnabled() {
        return true;
      }

      @Override
      public boolean checkpointSchedulerEnabled() {
        return true;
      }

      @Override
      public String checkpointInterval() {
        return "10m";
      }

      @Override
      public int checkpointRetainCount() {
        return 20;
      }

      @Override
      public boolean recoveryEnabled() {
        return true;
      }
    };
  }
}
