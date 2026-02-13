package com.sky.synome.core;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.kie.api.KieBase;
import org.kie.api.conf.EqualityBehaviorOption;
import org.kie.api.conf.EventProcessingOption;
import org.kie.internal.utils.KieHelper;

@ApplicationScoped
public class RuleCompiler {

  private static final Pattern PACKAGE_DECLARATION_PATTERN =
      Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)*)\\s*;?\\s*$");
  private static final String DEFAULT_RULE_SOURCE = "inline-rules.drl";

  public KieBase compile(String drl) {
    return compile(drl, inferSourcePath(drl));
  }

  public KieBase compile(String drl, String sourcePath) {
    if (drl == null || drl.isBlank()) {
      throw new IllegalArgumentException("DRL source must not be null or blank");
    }
    KieHelper helper = new KieHelper();
    helper.addContent(drl, sourcePath);

    var results = helper.verify();
    if (results.hasMessages(org.kie.api.builder.Message.Level.ERROR)) {
      var errors = new StringBuilder("DRL compilation errors:\n");
      results
          .getMessages(org.kie.api.builder.Message.Level.ERROR)
          .forEach(m -> errors.append("  ").append(m.getText()).append("\n"));
      throw new IllegalStateException(errors.toString());
    }

    return helper.build(EventProcessingOption.STREAM, EqualityBehaviorOption.EQUALITY);
  }

  private String inferSourcePath(String drl) {
    Matcher packageMatcher = PACKAGE_DECLARATION_PATTERN.matcher(drl);
    if (!packageMatcher.find()) {
      return DEFAULT_RULE_SOURCE;
    }
    return packageMatcher.group(1).replace('.', '/') + "/" + DEFAULT_RULE_SOURCE;
  }
}
