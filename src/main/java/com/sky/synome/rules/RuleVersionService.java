package com.sky.synome.rules;

import com.sky.synome.core.EngineSession;
import com.sky.synome.core.RuleCompiler;
import com.sky.synome.rules.RuleCompatibilityValidator.CompatibilityReport;
import com.sky.synome.rules.RuleHotSwapService.SwapOutcome;
import com.sky.synome.rules.RuleVersionStore.NewRuleVersion;
import com.sky.synome.rules.RuleVersionStore.RuleVersion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.kie.api.KieBase;
import org.kie.api.runtime.KieSession;

@ApplicationScoped
public class RuleVersionService {

  private static final String DEFAULT_SOURCE_PATH = "com/sky/synome/rules/inline-rules.drl";

  @Inject RuleCompiler ruleCompiler;

  @Inject RuleVersionStore ruleVersionStore;

  @Inject RuleCompatibilityValidator compatibilityValidator;

  @Inject RuleHotSwapService ruleHotSwapService;

  @Inject EngineSession engineSession;

  public ValidationReport validate(RuleSubmission submission) {
    BuildResult buildResult = buildCandidate(normalizeSources(submission));
    return buildResult.validationReport();
  }

  public UploadResult upload(RuleSubmission submission) {
    RuleSubmission normalized = normalizeSources(submission);
    BuildResult buildResult = buildCandidate(normalized);
    ValidationReport validationReport = buildResult.validationReport();
    if (!validationReport.valid()) {
      throw validationException(validationReport);
    }

    UUID versionId = UUID.randomUUID();
    String label =
        normalized.versionLabel() == null || normalized.versionLabel().isBlank()
            ? "rules-" + versionId.toString().substring(0, 8)
            : normalized.versionLabel();
    RuleVersion created =
        ruleVersionStore.create(
            new NewRuleVersion(
                versionId,
                label,
                normalized.drlFiles(),
                validationReport.checksum(),
                normalized.uploadedBy(),
                buildResult.compilationLog()));
    return new UploadResult(created, validationReport);
  }

  public List<RuleVersion> list(int limit, int offset) {
    return ruleVersionStore.list(limit, offset);
  }

  public RuleVersion activeVersion() {
    return ruleVersionStore
        .findActive()
        .orElseThrow(
            () ->
                new RuleVersionException(
                    "RULE_VERSION_NOT_FOUND", 404, "No active rule version is configured"));
  }

  public ActivationResult activate(UUID versionId) {
    RuleVersion version =
        ruleVersionStore
            .findById(versionId)
            .orElseThrow(
                () ->
                    new RuleVersionException(
                        "RULE_VERSION_NOT_FOUND",
                        404,
                        "Rule version not found",
                        Map.of("versionId", versionId)));

    BuildResult buildResult =
        buildCandidate(
            new RuleSubmission(
                version.versionLabel(), version.drlFiles(), null, null, version.uploadedBy()));
    if (!buildResult.validationReport().valid()) {
      throw validationException(buildResult.validationReport());
    }

    SwapOutcome swapOutcome =
        ruleHotSwapService.swapToVersion(version.versionId(), buildResult.kieBase());
    RuleVersion activated =
        ruleVersionStore
            .findById(version.versionId())
            .orElseThrow(() -> new IllegalStateException("Activated version disappeared"));

    return new ActivationResult(
        activated,
        swapOutcome.previousVersionId(),
        swapOutcome.checkpointId(),
        swapOutcome.replayedEvents(),
        swapOutcome.convergenceRulesFired(),
        swapOutcome.activatedAt());
  }

  public ActivationResult rollback(UUID versionId) {
    return activate(versionId);
  }

  private BuildResult buildCandidate(RuleSubmission submission) {
    String checksum = checksumFor(submission.drlFiles());
    KieBase candidateBase;
    List<String> compilationErrors = List.of();
    try {
      candidateBase = ruleCompiler.compile(submission.drlFiles());
    } catch (RuntimeException e) {
      compilationErrors = parseErrorLines(e.getMessage());
      ValidationReport report =
          new ValidationReport(false, checksum, compilationErrors, List.of(), false);
      return new BuildResult(null, report, formatCompilationLog(report, submission));
    }

    KieSession candidateSession = engineSession.createDetachedSession(candidateBase);
    try {
      CompatibilityReport compatibilityReport =
          compatibilityValidator.validate(candidateBase, candidateSession);
      ValidationReport report =
          new ValidationReport(
              compatibilityReport.compatible(),
              checksum,
              compilationErrors,
              compatibilityReport.errors(),
              true);
      return new BuildResult(candidateBase, report, formatCompilationLog(report, submission));
    } finally {
      engineSession.disposeSession(candidateSession);
    }
  }

  private RuleSubmission normalizeSources(RuleSubmission submission) {
    if (submission == null) {
      throw new RuleVersionException("RULE_VALIDATION_ERROR", 400, "Request body is required");
    }

    Map<String, String> normalized = new LinkedHashMap<>();
    if (submission.drlFiles() != null) {
      normalized.putAll(submission.drlFiles());
    }
    if (submission.drl() != null && !submission.drl().isBlank()) {
      String sourcePath =
          submission.sourcePath() == null || submission.sourcePath().isBlank()
              ? DEFAULT_SOURCE_PATH
              : submission.sourcePath();
      normalized.put(sourcePath, submission.drl());
    }

    if (normalized.isEmpty()) {
      throw new RuleVersionException(
          "RULE_VALIDATION_ERROR",
          400,
          "At least one DRL source must be provided",
          Map.of("field", "drlFiles|drl"));
    }

    normalized.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().isBlank());
    if (normalized.isEmpty()) {
      throw new RuleVersionException(
          "RULE_VALIDATION_ERROR", 400, "DRL source content must not be blank");
    }

    return new RuleSubmission(
        submission.versionLabel(), Map.copyOf(normalized), null, null, submission.uploadedBy());
  }

  private String checksumFor(Map<String, String> drlFiles) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      drlFiles.entrySet().stream()
          .sorted(Comparator.comparing(Map.Entry::getKey))
          .forEach(
              entry -> {
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
                digest.update(entry.getValue().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
              });

      byte[] hash = digest.digest();
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte value : hash) {
        String encoded = Integer.toHexString(value & 0xff);
        if (encoded.length() == 1) {
          hex.append('0');
        }
        hex.append(encoded);
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm unavailable", e);
    }
  }

  private List<String> parseErrorLines(String message) {
    if (message == null || message.isBlank()) {
      return List.of("Unknown compilation error");
    }
    List<String> lines = new ArrayList<>();
    for (String line : message.split("\\R")) {
      String trimmed = line.trim();
      if (!trimmed.isBlank() && !"DRL compilation errors:".equals(trimmed)) {
        lines.add(trimmed);
      }
    }
    return lines.isEmpty() ? List.of(message.trim()) : List.copyOf(lines);
  }

  private String formatCompilationLog(ValidationReport report, RuleSubmission submission) {
    StringBuilder builder = new StringBuilder();
    builder.append("validatedAt=").append(Instant.now()).append('\n');
    builder.append("sources=").append(submission.drlFiles().size()).append('\n');
    builder.append("compiled=").append(report.compiled()).append('\n');
    builder.append("compatible=").append(report.compatible()).append('\n');
    if (!report.compilationErrors().isEmpty()) {
      builder.append("compilationErrors=").append(report.compilationErrors()).append('\n');
    }
    if (!report.compatibilityErrors().isEmpty()) {
      builder.append("compatibilityErrors=").append(report.compatibilityErrors()).append('\n');
    }
    return builder.toString();
  }

  private RuleVersionException validationException(ValidationReport report) {
    return new RuleVersionException(
        "RULE_VALIDATION_ERROR",
        400,
        "Rule validation failed",
        Map.of(
            "checksum", report.checksum(),
            "compilationErrors", report.compilationErrors(),
            "compatibilityErrors", report.compatibilityErrors()));
  }

  public record ValidationReport(
      boolean valid,
      String checksum,
      List<String> compilationErrors,
      List<String> compatibilityErrors,
      boolean compiled) {

    public boolean compatible() {
      return compatibilityErrors == null || compatibilityErrors.isEmpty();
    }
  }

  public record RuleSubmission(
      String versionLabel,
      Map<String, String> drlFiles,
      String drl,
      String sourcePath,
      String uploadedBy) {}

  private record BuildResult(
      KieBase kieBase, ValidationReport validationReport, String compilationLog) {}

  public record ActivationResult(
      RuleVersion activeVersion,
      UUID previousVersionId,
      UUID checkpointId,
      int replayedEvents,
      int convergenceRulesFired,
      Instant activatedAt) {}

  public record UploadResult(RuleVersion version, ValidationReport validationReport) {}
}
