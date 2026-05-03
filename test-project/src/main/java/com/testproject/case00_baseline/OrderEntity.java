package com.testproject.case00_baseline;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class OrderEntity {
    @Id
    private Long id;
    private String reference;
    private double amount;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }
    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = amount; }
}
