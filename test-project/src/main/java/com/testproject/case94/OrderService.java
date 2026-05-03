package com.testproject.case94;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    @Autowired
    private PriceProvider priceProvider;

    private Cache cache;

    private void primeIfNeeded() {
        if (cache == null) {
            cache = new Cache();
        }
    }

    // @TestTarget
    // Auto-init dans la méthode cible — primeIfNeeded() initialise `cache`
    // AVANT toute lecture. Stratégie attendue : IMPLICIT.
    public OrderDTO calculate(String sku) {
        primeIfNeeded();
        if (!cache.has(sku)) {
            cache.put(sku, priceProvider.getPrice(sku));
        }
        return new OrderDTO(sku, cache.get(sku));
    }
}
