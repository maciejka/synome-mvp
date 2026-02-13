package com.sky.synome.checkpoint;

import java.util.Map;

public record CheckpointRegistryEntry(String factType, Map<String, Object> data) {}
