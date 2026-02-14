package com.sky.synome.security;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@ApplicationScoped
public class ApiKeyHashingService {

  public String hash(String rawKey) {
    if (rawKey == null || rawKey.isBlank()) {
      throw new IllegalArgumentException("API key must not be blank");
    }

    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(rawKey.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  public boolean verify(String rawKey, String expectedHash) {
    if (expectedHash == null || expectedHash.isBlank()) {
      return false;
    }
    byte[] provided = hash(rawKey).getBytes(StandardCharsets.UTF_8);
    byte[] expected = expectedHash.getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(provided, expected);
  }
}
