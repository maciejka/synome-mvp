package com.sky.synome.core;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Comparator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.kie.api.KieBase;
import org.kie.api.builder.Message;
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
    if (sourcePath == null || sourcePath.isBlank()) {
      throw new IllegalArgumentException("sourcePath must not be null or blank");
    }
    return compile(Map.of(sourcePath, drl));
  }

  public KieBase compile(Map<String, String> drlBySourcePath) {
    if (drlBySourcePath == null || drlBySourcePath.isEmpty()) {
      throw new IllegalArgumentException("DRL sources must not be null or empty");
    }

    KieHelper helper = new KieHelper();
    drlBySourcePath.entrySet().stream()
        .sorted(Comparator.comparing(Map.Entry::getKey))
        .forEach(
            entry -> {
              String sourcePath = entry.getKey();
              String drl = entry.getValue();
              if (sourcePath == null || sourcePath.isBlank()) {
                throw new IllegalArgumentException("DRL source path must not be null or blank");
              }
              if (drl == null || drl.isBlank()) {
                throw new IllegalArgumentException(
                    "DRL source must not be null or blank for sourcePath=" + sourcePath);
              }
              helper.addContent(drl, sourcePath);
            });

    var results = helper.verify();
    if (results.hasMessages(org.kie.api.builder.Message.Level.ERROR)) {
      throw new IllegalStateException(
          buildCompilationErrorMessage(results.getMessages(Message.Level.ERROR)));
    }

    return helper.build(EventProcessingOption.STREAM, EqualityBehaviorOption.EQUALITY);
  }

  private String buildCompilationErrorMessage(java.util.List<Message> errors) {
    var builder = new StringBuilder("DRL compilation errors:\n");
    for (Message error : errors) {
      builder.append("  ").append(error.getText()).append("\n");
    }
    return builder.toString();
  }

  private String inferSourcePath(String drl) {
    Matcher packageMatcher = PACKAGE_DECLARATION_PATTERN.matcher(drl);
    if (!packageMatcher.find()) {
      return DEFAULT_RULE_SOURCE;
    }
    return packageMatcher.group(1).replace('.', '/') + "/" + DEFAULT_RULE_SOURCE;
  }
}
