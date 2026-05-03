package com.testproject.case93;

public class OrderRequest {
    private final Long id;
    private final String key;

    public OrderRequest(Long id, String key) {
        this.id = id;
        this.key = key;
    }

    public Long getId() { return id; }
    public String getKey() { return key; }
}
