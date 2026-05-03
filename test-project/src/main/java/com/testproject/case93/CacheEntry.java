package com.testproject.case93;

public class CacheEntry {
    private final String key;
    private final String value;

    public CacheEntry(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() { return key; }
    public String getValue() { return value; }
}
