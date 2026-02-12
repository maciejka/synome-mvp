package com.sky.synome.api.dto;

import java.util.Map;

public record FactResponse(String factKey, String factType, Map<String, Object> data) {}
