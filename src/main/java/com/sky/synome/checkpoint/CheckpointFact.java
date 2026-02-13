package com.sky.synome.checkpoint;

import java.util.Map;

public record CheckpointFact(String factKey, String factType, Map<String, Object> data) {}
