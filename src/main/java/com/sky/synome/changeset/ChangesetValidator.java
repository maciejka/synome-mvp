package com.sky.synome.changeset;

import com.sky.synome.config.EngineConfig;
import com.sky.synome.core.EngineSession;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class ChangesetValidator {

  private static final String DRL_PACKAGE = "com.sky.synome.rules";

  @Inject EngineSession engineSession;

  @Inject EngineConfig engineConfig;

  public void validate(Changeset changeset) {
    List<String> errors = new ArrayList<>();

    if (changeset.id() == null) {
      errors.add("Changeset ID is required");
    }
    if (changeset.entries() == null || changeset.entries().isEmpty()) {
      errors.add("Changeset must have at least one entry");
    }

    if (changeset.entries() != null) {
      for (int i = 0; i < changeset.entries().size(); i++) {
        validateEntry(changeset.entries().get(i), i, errors);
      }
    }

    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
  }

  private void validateEntry(ChangesetEntry entry, int index, List<String> errors) {
    String prefix = "entries[" + index + "]: ";

    if (entry.kind() == null) {
      errors.add(prefix + "kind is required");
    }
    if (entry.action() == null) {
      errors.add(prefix + "action is required");
    }

    if (entry.kind() == null || entry.action() == null) {
      return;
    }

    switch (entry.kind()) {
      case FACT -> validateFactEntry(entry, prefix, errors);
      case EVENT -> validateEventEntry(entry, prefix, errors);
    }
  }

  private void validateFactEntry(ChangesetEntry entry, String prefix, List<String> errors) {
    switch (entry.action()) {
      case UPSERT -> {
        validateRequiredText(entry.factKey(), "factKey", prefix, errors);
        validateRequiredFactType(entry.factType(), prefix, errors);
        validateRequiredData(entry.data(), prefix, errors);
      }
      case DELETE -> {
        validateRequiredText(entry.factKey(), "factKey", prefix, errors);
        validateKnownFactTypeIfPresent(entry.factType(), prefix, errors);
      }
      default -> errors.add(prefix + "invalid kind/action combination FACT/" + entry.action());
    }
  }

  private void validateEventEntry(ChangesetEntry entry, String prefix, List<String> errors) {
    if (entry.action() != ChangesetAction.EMIT) {
      errors.add(prefix + "invalid kind/action combination EVENT/" + entry.action());
      return;
    }

    validateRequiredText(entry.entryPoint(), "entryPoint", prefix, errors);
    validateAllowedEntrypoint(entry.entryPoint(), prefix, errors);
    validateRequiredFactType(entry.factType(), prefix, errors);
    if (entry.timestamp() == null) {
      errors.add(prefix + "timestamp is required for EVENT/EMIT");
    }
    validateRequiredData(entry.data(), prefix, errors);
  }

  private void validateRequiredFactType(String factType, String prefix, List<String> errors) {
    if (factType == null || factType.isBlank()) {
      errors.add(prefix + "factType is required");
      return;
    }
    validateKnownFactType(factType, prefix, errors);
  }

  private void validateKnownFactTypeIfPresent(String factType, String prefix, List<String> errors) {
    if (factType == null || factType.isBlank()) {
      return;
    }
    validateKnownFactType(factType, prefix, errors);
  }

  private void validateKnownFactType(String factType, String prefix, List<String> errors) {
    var knownFactType = engineSession.kieBase().getFactType(DRL_PACKAGE, factType);
    if (knownFactType == null) {
      errors.add(prefix + "unknown factType '" + factType + "'");
    }
  }

  private void validateRequiredText(
      String value, String fieldName, String prefix, List<String> errors) {
    if (value == null || value.isBlank()) {
      errors.add(prefix + fieldName + " is required");
    }
  }

  private void validateRequiredData(
      java.util.Map<String, Object> data, String prefix, List<String> errors) {
    if (data == null || data.isEmpty()) {
      errors.add(prefix + "data is required");
    }
  }

  private void validateAllowedEntrypoint(String entryPoint, String prefix, List<String> errors) {
    Set<String> allowedEntrypoints = configuredEntrypoints();
    if (allowedEntrypoints.isEmpty()) {
      return;
    }
    if (entryPoint == null || !allowedEntrypoints.contains(entryPoint)) {
      errors.add(prefix + "entryPoint is not enabled by engine.event-entrypoints");
    }
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

  public static class ValidationException extends RuntimeException {
    private final List<String> errors;

    public ValidationException(List<String> errors) {
      super("Changeset validation failed: " + String.join("; ", errors));
      this.errors = errors;
    }

    public List<String> errors() {
      return errors;
    }
  }
}
