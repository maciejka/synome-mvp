package com.sky.synome.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class FactRegistryTest {

  @Test
  void putGetRemoveAndClearWork() {
    FactRegistry registry = new FactRegistry();
    FactRegistry.FactEntry entry = new FactRegistry.FactEntry(null, "Customer", Map.of("id", "C1"));

    registry.put("customer:C1", entry);

    assertTrue(registry.contains("customer:C1"));
    assertEquals(1, registry.size());
    assertEquals(entry, registry.get("customer:C1"));
    assertEquals(1, registry.entries().size());

    FactRegistry.FactEntry removed = registry.remove("customer:C1");
    assertEquals(entry, removed);
    assertFalse(registry.contains("customer:C1"));
    assertNull(registry.get("customer:C1"));

    registry.put("customer:C2", new FactRegistry.FactEntry(null, "Customer", Map.of("id", "C2")));
    assertEquals(1, registry.size());
    registry.clear();
    assertEquals(0, registry.size());
  }

  @Test
  void countByTypeAggregatesFacts() {
    FactRegistry registry = new FactRegistry();
    registry.put("customer:C1", new FactRegistry.FactEntry(null, "Customer", Map.of()));
    registry.put("customer:C2", new FactRegistry.FactEntry(null, "Customer", Map.of()));
    registry.put("account:A1", new FactRegistry.FactEntry(null, "Account", Map.of()));

    Map<String, Long> counts = registry.countByType();

    assertNotNull(counts);
    assertEquals(2L, counts.get("Customer"));
    assertEquals(1L, counts.get("Account"));
  }
}
