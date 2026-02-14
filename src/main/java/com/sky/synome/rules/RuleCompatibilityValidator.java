package com.sky.synome.rules;

import com.sky.synome.config.EngineConfig;
import com.sky.synome.core.EngineSession;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.kie.api.KieBase;
import org.kie.api.definition.type.FactField;
import org.kie.api.definition.type.FactType;
import org.kie.api.runtime.KieSession;

@ApplicationScoped
public class RuleCompatibilityValidator {

  @Inject EngineSession engineSession;

  @Inject EngineConfig engineConfig;

  public CompatibilityReport validate(KieBase candidateBase, KieSession candidateSession) {
    List<String> errors = new ArrayList<>();

    Map<String, List<String>> requiredFactFields = requiredFactFields();
    for (Map.Entry<String, List<String>> entry : requiredFactFields.entrySet()) {
      FactType candidateFact = candidateBase.getFactType(EngineSession.DRL_PACKAGE, entry.getKey());
      if (candidateFact == null) {
        errors.add("missing factType '" + entry.getKey() + "'");
        continue;
      }
      for (String field : entry.getValue()) {
        if (candidateFact.getField(field) == null) {
          errors.add("missing field '" + entry.getKey() + "." + field + "'");
        }
      }
    }

    for (String entryPoint : configuredEntrypoints()) {
      if (candidateSession.getEntryPoint(entryPoint) == null) {
        errors.add("missing event entryPoint '" + entryPoint + "'");
      }
    }

    return new CompatibilityReport(errors.isEmpty(), errors);
  }

  private Map<String, List<String>> requiredFactFields() {
    Map<String, List<String>> requirements = new HashMap<>();
    var activePackage = engineSession.kieBase().getKiePackage(EngineSession.DRL_PACKAGE);
    if (activePackage == null) {
      return requirements;
    }

    for (FactType factType : activePackage.getFactTypes()) {
      List<String> fields = new ArrayList<>();
      for (FactField field : factType.getFields()) {
        fields.add(field.getName());
      }
      requirements.put(factType.getSimpleName(), fields);
    }
    return requirements;
  }

  private Set<String> configuredEntrypoints() {
    String configured = engineConfig.eventEntrypoints();
    if (configured == null || configured.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(configured.split(","))
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .collect(java.util.stream.Collectors.toSet());
  }

  public record CompatibilityReport(boolean compatible, List<String> errors) {}
}
