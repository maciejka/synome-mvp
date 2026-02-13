package com.sky.synome.checkpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MessagePackLz4CheckpointSerializerTest {

  private final MessagePackLz4CheckpointSerializer serializer =
      new MessagePackLz4CheckpointSerializer();

  @Test
  void factsRoundTripPreservesPayload() {
    List<CheckpointFact> facts =
        List.of(
            new CheckpointFact(
                "customer:C-RT-1",
                "Customer",
                Map.of(
                    "customerId", "C-RT-1",
                    "name", "Round Trip",
                    "tier", "STANDARD",
                    "balance", 42.0)));

    byte[] encoded = serializer.serializeFacts(facts);
    List<CheckpointFact> decoded = serializer.deserializeFacts(encoded);

    assertEquals(1, decoded.size());
    assertEquals("customer:C-RT-1", decoded.get(0).factKey());
    assertEquals("Customer", decoded.get(0).factType());
    assertEquals("Round Trip", decoded.get(0).data().get("name"));
  }

  @Test
  void registryRoundTripPreservesKeysAndTypes() {
    Map<String, CheckpointRegistryEntry> registry = new LinkedHashMap<>();
    registry.put(
        "customer:C-RT-2",
        new CheckpointRegistryEntry(
            "Customer",
            Map.of(
                "customerId", "C-RT-2",
                "name", "Registry Round Trip",
                "tier", "PREMIUM",
                "balance", 900.0)));

    byte[] encoded = serializer.serializeRegistry(registry);
    Map<String, CheckpointRegistryEntry> decoded = serializer.deserializeRegistry(encoded);

    assertEquals(1, decoded.size());
    assertTrue(decoded.containsKey("customer:C-RT-2"));
    assertEquals("Customer", decoded.get("customer:C-RT-2").factType());
  }

  @Test
  void malformedBlobFailsDeserialization() {
    byte[] corrupted = new byte[] {0, 0, 0, 8, 1, 2, 3};

    assertThrows(CheckpointException.class, () -> serializer.deserializeFacts(corrupted));
  }
}
