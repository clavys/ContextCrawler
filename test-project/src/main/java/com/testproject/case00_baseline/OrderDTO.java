package com.testproject.case00_baseline;

public class OrderDTO {
    private Long id;
    private String reference;
    private double amount;

    public OrderDTO() {}

    public OrderDTO(Long id, String reference, double amount) {
        this.id = id;
        this.reference = reference;
        this.amount = amount;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }
    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = amount; }
}
