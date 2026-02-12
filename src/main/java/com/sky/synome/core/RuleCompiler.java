package com.sky.synome.core;

import jakarta.enterprise.context.ApplicationScoped;
import org.kie.api.KieBase;
import org.kie.api.conf.EqualityBehaviorOption;
import org.kie.api.conf.EventProcessingOption;
import org.kie.internal.utils.KieHelper;

@ApplicationScoped
public class RuleCompiler {

  public KieBase compile(String drl) {
    KieHelper helper = new KieHelper();
    helper.addContent(drl, org.kie.api.io.ResourceType.DRL);

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
}
