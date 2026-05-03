package com.testproject.case95;

import java.util.HashMap;
import java.util.Map;

public class Cache {
    private final Config config;
    private final Map<String, String> data = new HashMap<>();

    public Cache(Config config) {
        this.config = config;
    }

    public String get(String key) {
        return data.getOrDefault(key, "default-" + config.getRegion());
    }
}
