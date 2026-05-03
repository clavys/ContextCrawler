package com.testproject.case92;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    @Autowired
    private PricingGateway pricingGateway;

    private Config config;

    // Méthode publique avec arguments — protocole CALL_PUBLIC_WITH_ARGS.
    public void configure(int timeoutMs, String region) {
        Config c = new Config(timeoutMs, region);
        c.setRate(pricingGateway.fetchRate(region));
        this.config = c;
    }

    // @TestTarget
    public OrderDTO calculate(OrderRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is null");
        }
        double finalAmount = config.apply(request.getRawAmount());
        return new OrderDTO(request.getId(), finalAmount, config.getRegion());
    }
}
