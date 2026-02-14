package com.sky.synome.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ApiKeyHashingServiceTest {

  private final ApiKeyHashingService hashingService = new ApiKeyHashingService();

  @Test
  void hashAndVerifyAreStable() {
    String hash = hashingService.hash("sample-secret");

    assertEquals(hash, hashingService.hash("sample-secret"));
    assertTrue(hashingService.verify("sample-secret", hash));
    assertTrue(!hashingService.verify("different-secret", hash));
  }

  @Test
  void permissionParsingSupportsCsvAndArray() {
    Set<ApiPermission> fromCsv = ApiPermission.parseCsv("CHANGESET_WRITE,FACT_READ");
    Set<ApiPermission> fromArray = ApiPermission.parseArray(new String[] {"RULE_ADMIN"});

    assertTrue(fromCsv.contains(ApiPermission.CHANGESET_WRITE));
    assertTrue(fromCsv.contains(ApiPermission.FACT_READ));
    assertTrue(fromArray.contains(ApiPermission.RULE_ADMIN));
  }
}
