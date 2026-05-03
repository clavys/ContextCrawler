package com.testproject.case91;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OrderService {

    @Autowired
    private DiscountRepository repository;

    private DiscountCache cache;

    // @PostConstruct prioritaire — protocole CALL_POST_CONSTRUCT(init).
    @PostConstruct
    public void init() {
        primeCache();
    }

    private void primeCache() {
        DiscountCache c = new DiscountCache();
        List<DiscountEntity> entities = repository.findAllActive();
        c.warm(entities);
        this.cache = c;
    }

    // @TestTarget
    public OrderDTO calculate(Long orderId, String discountCode, double rawAmount) {
        double percent = cache.lookup(discountCode);
        double finalAmount = rawAmount * (1.0 - percent / 100.0);
        return new OrderDTO(orderId, finalAmount);
    }
}
