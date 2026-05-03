package com.testproject.case91;

public class OrderDTO {
    private final Long orderId;
    private final double finalAmount;

    public OrderDTO(Long orderId, double finalAmount) {
        this.orderId = orderId;
        this.finalAmount = finalAmount;
    }

    public Long getOrderId() { return orderId; }
    public double getFinalAmount() { return finalAmount; }
}
