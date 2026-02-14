package com.sky.synome.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sky.synome.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.kie.api.definition.type.FactType;
import org.kie.api.runtime.rule.FactHandle;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class EngineSessionTest {

  private static final String DRL_PACKAGE = "com.sky.synome.rules";

  @Inject EngineSession engineSession;

  @Test
  void sessionStartsAndDrlCompiles() {
    assertNotNull(engineSession.kieBase());
    assertNotNull(engineSession.kieSession());
    assertNotNull(engineSession.factRegistry());
    assertNotNull(engineSession.sessionLock());
    assertNotNull(engineSession.provenanceCollector());
  }

  @Test
  void factTypesAreResolvable() {
    assertNotNull(engineSession.kieBase().getFactType(DRL_PACKAGE, "Customer"));
    assertNotNull(engineSession.kieBase().getFactType(DRL_PACKAGE, "Account"));
    assertNotNull(engineSession.kieBase().getFactType(DRL_PACKAGE, "HighValueCustomer"));
  }

  @Test
  void insertFactAndFireRule() throws Exception {
    var session = engineSession.kieSession();
    var collector = engineSession.provenanceCollector();

    FactType customerType = engineSession.kieBase().getFactType(DRL_PACKAGE, "Customer");
    Object customer = customerType.newInstance();
    customerType.set(customer, "customerId", "TEST-C1");
    customerType.set(customer, "name", "Test User");
    customerType.set(customer, "tier", "PREMIUM");
    customerType.set(customer, "balance", 0.0);

    FactType accountType = engineSession.kieBase().getFactType(DRL_PACKAGE, "Account");
    Object account = accountType.newInstance();
    accountType.set(account, "accountId", "TEST-A1");
    accountType.set(account, "customerId", "TEST-C1");
    accountType.set(account, "type", "SAVINGS");
    accountType.set(account, "balance", 200000.0);

    FactHandle ch = session.insert(customer);
    FactHandle ah = session.insert(account);

    collector.startChangeset(UUID.randomUUID());
    int fired = session.fireAllRules();
    var capture = collector.stopAndSnapshot();

    assertTrue(fired > 0, "At least one rule should fire");
    assertFalse(capture.derivedFacts().isEmpty(), "Should have derived facts");
    assertEquals("HighValueCustomer", capture.derivedFacts().get(0).factType());

    // Cleanup to not affect other tests
    session.delete(ch);
    session.delete(ah);
    session.fireAllRules();
  }
}
