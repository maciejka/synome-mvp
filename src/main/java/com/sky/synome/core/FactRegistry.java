package com.sky.synome.core;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.kie.api.runtime.rule.FactHandle;

public class FactRegistry {

  public record FactEntry(FactHandle handle, String factType, Map<String, Object> data) {}

  private final ConcurrentHashMap<String, FactEntry> facts = new ConcurrentHashMap<>();

  public void put(String factKey, FactEntry entry) {
    facts.put(factKey, entry);
  }

  public FactEntry get(String factKey) {
    return facts.get(factKey);
  }

  public FactEntry remove(String factKey) {
    return facts.remove(factKey);
  }

  public boolean contains(String factKey) {
    return facts.containsKey(factKey);
  }

  public Collection<Map.Entry<String, FactEntry>> entries() {
    return facts.entrySet();
  }

  public int size() {
    return facts.size();
  }

  public void clear() {
    facts.clear();
  }

  public Map<String, Long> countByType() {
    var counts = new java.util.HashMap<String, Long>();
    for (FactEntry entry : facts.values()) {
      counts.merge(entry.factType(), 1L, Long::sum);
    }
    return counts;
  }
}
