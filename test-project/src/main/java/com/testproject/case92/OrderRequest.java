package com.testproject.case92;

public class OrderRequest {
    private final Long id;
    private final double rawAmount;

    public OrderRequest(Long id, double rawAmount) {
        this.id = id;
        this.rawAmount = rawAmount;
    }

    public Long getId() { return id; }
    public double getRawAmount() { return rawAmount; }
}
