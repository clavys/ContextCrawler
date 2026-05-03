package com.testproject.case91;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class DiscountEntity {
    @Id
    private Long id;
    private String code;
    private double percent;

    public Long getId() { return id; }
    public String getCode() { return code; }
    public double getPercent() { return percent; }
    public void setId(Long id) { this.id = id; }
    public void setCode(String code) { this.code = code; }
    public void setPercent(double percent) { this.percent = percent; }
}
