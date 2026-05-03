package com.testproject.case94;

import java.util.HashMap;
import java.util.Map;

public class Cache {
    private final Map<String, Double> entries = new HashMap<>();

    public boolean has(String sku) { return entries.containsKey(sku); }
    public double get(String sku) { return entries.getOrDefault(sku, 0.0); }
    public void put(String sku, double price) { entries.put(sku, price); }
}
