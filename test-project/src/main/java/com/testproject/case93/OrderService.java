package com.testproject.case93;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OrderService extends AbstractCacheService {

    @Autowired
    private Loader loader;

    // Point d'entrée public — chaîne start → startInternal → warmup → buildCache.
    // Stratégie attendue : CALL_PUBLIC_TRANSITIVE.
    public void start() {
        startInternal();
    }

    private void startInternal() {
        warmup();
    }

    @Override
    protected void warmup() {
        this.cache = buildCache();
    }

    private Cache buildCache() {
        List<CacheEntry> entries = loader.load();
        return new Cache(entries);
    }

    // @TestTarget
    public OrderDTO calculate(OrderRequest request) {
        String value = cache.get(request.getKey());
        return new OrderDTO(request.getId(), value);
    }
}
