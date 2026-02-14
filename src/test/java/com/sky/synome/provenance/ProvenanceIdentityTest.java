package com.sky.synome.provenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProvenanceIdentityTest {

  @Test
  void baseFactIdIsDeterministic() {
    assertEquals(
        ProvenanceIdentity.baseFactId("customer:C-1"),
        ProvenanceIdentity.baseFactId("customer:C-1"));
  }

  @Test
  void derivedAndEventIdsArePrefixedAndUnique() {
    String derivedFirst = ProvenanceIdentity.derivedFactId();
    String derivedSecond = ProvenanceIdentity.derivedFactId();
    String eventFactId = ProvenanceIdentity.eventFactId();

    assertTrue(derivedFirst.startsWith("derived:"));
    assertTrue(eventFactId.startsWith("event:"));
    assertNotEquals(derivedFirst, derivedSecond);
  }
}
