package com.testproject.case92;

// Construite par OrderService.configure(...) — pas un @Bean Spring, donc
// pas un mock externe. C'est un POJO de config local, à instancier en test.
public class Config {
    private final int timeoutMs;
    private final String region;
    private double rate;

    public Config(int timeoutMs, String region) {
        this.timeoutMs = timeoutMs;
        this.region = region;
    }

    public int getTimeoutMs() { return timeoutMs; }
    public String getRegion() { return region; }
    public double getRate() { return rate; }
    public void setRate(double rate) { this.rate = rate; }

    public double apply(double amount) {
        return amount * rate;
    }
}
