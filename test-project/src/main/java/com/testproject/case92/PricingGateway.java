package com.testproject.case92;

import org.springframework.stereotype.Component;

@Component
public interface PricingGateway {
    double fetchRate(String region);
}
