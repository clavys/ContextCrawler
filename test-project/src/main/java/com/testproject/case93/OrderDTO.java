package com.testproject.case93;

public class OrderDTO {
    private final Long id;
    private final String resolvedValue;

    public OrderDTO(Long id, String resolvedValue) {
        this.id = id;
        this.resolvedValue = resolvedValue;
    }

    public Long getId() { return id; }
    public String getResolvedValue() { return resolvedValue; }
}
