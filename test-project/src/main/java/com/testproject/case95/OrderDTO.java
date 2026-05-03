package com.testproject.case95;

public class OrderDTO {
    private final String key;
    private final String value;

    public OrderDTO(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() { return key; }
    public String getValue() { return value; }
}
