package com.sky.synome.api.dto;

public record DerivedFactSummary(
    String factId, String factType, String producedByRule, String summary) {}
