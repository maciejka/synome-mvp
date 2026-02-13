package com.sky.synome.checkpoint;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import org.msgpack.jackson.dataformat.MessagePackFactory;

@ApplicationScoped
public class MessagePackLz4CheckpointSerializer implements CheckpointSerializer {

  private static final String FORMAT = "msgpack+lz4";

  private static final TypeReference<List<CheckpointFact>> FACT_LIST_TYPE =
      new TypeReference<>() {};

  private static final TypeReference<Map<String, CheckpointRegistryEntry>> REGISTRY_TYPE =
      new TypeReference<>() {};

  private static final ObjectMapper MESSAGE_PACK_MAPPER =
      new ObjectMapper(new MessagePackFactory());

  private static final LZ4Factory LZ4_FACTORY = LZ4Factory.fastestInstance();

  @Override
  public String format() {
    return FORMAT;
  }

  @Override
  public byte[] serializeFacts(List<CheckpointFact> facts) {
    return writeCompressed(facts);
  }

  @Override
  public List<CheckpointFact> deserializeFacts(byte[] compressedBlob) {
    return readCompressed(compressedBlob, FACT_LIST_TYPE);
  }

  @Override
  public byte[] serializeRegistry(Map<String, CheckpointRegistryEntry> registry) {
    return writeCompressed(registry);
  }

  @Override
  public Map<String, CheckpointRegistryEntry> deserializeRegistry(byte[] compressedBlob) {
    return readCompressed(compressedBlob, REGISTRY_TYPE);
  }

  private byte[] writeCompressed(Object value) {
    try {
      byte[] messagePack = MESSAGE_PACK_MAPPER.writeValueAsBytes(value);
      LZ4Compressor compressor = LZ4_FACTORY.fastCompressor();
      int maxLength = compressor.maxCompressedLength(messagePack.length);
      byte[] compressed = new byte[maxLength];
      int compressedSize =
          compressor.compress(messagePack, 0, messagePack.length, compressed, 0, maxLength);

      ByteBuffer buffer = ByteBuffer.allocate(4 + compressedSize);
      buffer.putInt(messagePack.length);
      buffer.put(compressed, 0, compressedSize);
      return buffer.array();
    } catch (Exception e) {
      throw new CheckpointException("Failed to serialize checkpoint payload", e);
    }
  }

  private <T> T readCompressed(byte[] compressedBlob, TypeReference<T> type) {
    if (compressedBlob == null || compressedBlob.length < 4) {
      throw new CheckpointException("Checkpoint blob is null or truncated");
    }
    try {
      ByteBuffer buffer = ByteBuffer.wrap(compressedBlob);
      int decompressedSize = buffer.getInt();
      if (decompressedSize < 0) {
        throw new CheckpointException("Checkpoint blob contains invalid decompressed size");
      }
      byte[] compressed = new byte[buffer.remaining()];
      buffer.get(compressed);

      byte[] restored = new byte[decompressedSize];
      LZ4FastDecompressor decompressor = LZ4_FACTORY.fastDecompressor();
      decompressor.decompress(compressed, 0, restored, 0, decompressedSize);
      return MESSAGE_PACK_MAPPER.readValue(restored, type);
    } catch (CheckpointException e) {
      throw e;
    } catch (Exception e) {
      throw new CheckpointException("Failed to deserialize checkpoint payload", e);
    }
  }
}
