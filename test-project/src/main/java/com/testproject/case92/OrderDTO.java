package com.testproject.case92;

public class OrderDTO {
    private final Long id;
    private final double finalAmount;
    private final String region;

    public OrderDTO(Long id, double finalAmount, String region) {
        this.id = id;
        this.finalAmount = finalAmount;
        this.region = region;
    }

    public Long getId() { return id; }
    public double getFinalAmount() { return finalAmount; }
    public String getRegion() { return region; }
}
