package com.testproject.case93;

// Super-classe utilisateur — la stratégie doit la remonter dans HierarchieComplete
// (STRATEGIE.md §3.1, BLOC 1).
public abstract class AbstractCacheService {

    protected Cache cache;

    protected void warmup() {
        // Hook surchargé par la sous-classe.
    }
}
