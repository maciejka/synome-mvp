package com.sky.synome.checkpoint;

import java.util.List;
import java.util.Map;

public interface CheckpointSerializer {

  String format();

  byte[] serializeFacts(List<CheckpointFact> facts);

  List<CheckpointFact> deserializeFacts(byte[] compressedBlob);

  byte[] serializeRegistry(Map<String, CheckpointRegistryEntry> registry);

  Map<String, CheckpointRegistryEntry> deserializeRegistry(byte[] compressedBlob);
}
