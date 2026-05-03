package com.testproject.case91;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Auto-construite par OrderService.init() — sa présence ne doit PAS apparaître
// dans les mocks (voir EXPECTED_PROMPTS.md case91).
public class DiscountCache {
    private final Map<String, Double> cache = new HashMap<>();

    public void warm(List<DiscountEntity> entities) {
        for (DiscountEntity e : entities) {
            cache.put(e.getCode(), e.getPercent());
        }
    }

    public double lookup(String code) {
        return cache.getOrDefault(code, 0.0);
    }
}
