package com.testproject.case95;

import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private Cache cache;

    // primeCache prend un paramètre Config et est privée. AUCUNE méthode
    // publique du SUT ne l'appelle. Aucune source d'init publique pour `cache`.
    // Stratégie attendue : UNTESTABLE_AS_IS (raison : champ assigné uniquement
    // par une méthode privée non appelée transitivement).
    private void primeCache(Config c) {
        this.cache = new Cache(c);
    }

    // @TestTarget
    public OrderDTO calculate(String key) {
        return new OrderDTO(key, cache.get(key));
    }
}
