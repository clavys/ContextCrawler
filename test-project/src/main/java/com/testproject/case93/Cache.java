package com.testproject.case93;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Cache {
    private final Map<String, String> entries = new HashMap<>();

    public Cache(List<CacheEntry> initial) {
        for (CacheEntry e : initial) {
            entries.put(e.getKey(), e.getValue());
        }
    }

    public String get(String key) {
        return entries.get(key);
    }
}
