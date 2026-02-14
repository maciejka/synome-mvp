package com.sky.synome.provenance;

import java.time.Instant;

public record ProvenanceRetraction(
    String factId, String ruleName, Instant retractedAt, String retractionReason) {}
