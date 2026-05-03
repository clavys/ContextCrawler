package com.testproject.case94;

import org.springframework.stereotype.Component;

@Component
public interface PriceProvider {
    double getPrice(String sku);
}
