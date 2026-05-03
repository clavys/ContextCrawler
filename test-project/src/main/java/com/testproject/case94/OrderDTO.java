package com.testproject.case94;

public class OrderDTO {
    private final String sku;
    private final double price;

    public OrderDTO(String sku, double price) {
        this.sku = sku;
        this.price = price;
    }

    public String getSku() { return sku; }
    public double getPrice() { return price; }
}
