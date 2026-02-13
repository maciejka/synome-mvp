package com.sky.synome.core;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RuleCompilerTest {

  private final RuleCompiler compiler = new RuleCompiler();

  @Test
  void compilesValidDrl() {
    String drl =
        """
        package test.rules

        declare Sample
          id : String
        end

        rule \"noop\"
        when
          Sample()
        then
        end
        """;

    assertNotNull(compiler.compile(drl));
  }

  @Test
  void blankDrlIsRejected() {
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> compiler.compile("   "));
    assertTrue(ex.getMessage().contains("must not be null or blank"));
  }
}
