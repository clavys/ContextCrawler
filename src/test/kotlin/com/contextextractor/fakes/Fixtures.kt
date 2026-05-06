package com.contextextractor.fakes

import com.contextextractor.core.extractor.ResolvedType

// Factory functions par cas — chaque fixture mirroir le code Java de
// test-project/src/main/java/com/testproject/caseXX/. L'objectif est que
// les tests JUnit 5 puissent valider que les data classes du `core/`
// peuvent représenter les 7 scénarios de référence sans dépendre de PSI.
//
// À l'étape 4, ces fixtures seront aussi consommées par RecursiveDeepStrategy.

object Fixtures {

    // -- case00 baseline ------------------------------------------------------
    // OrderService(@Service) + OrderRepository (interface JPA) + OrderEntity + OrderDTO.
    fun case00Baseline(): FakeIntrospector = fixture {
        val pkg = "com.testproject.case00_baseline"

        klass("$pkg.OrderEntity", annotations = listOf("jakarta.persistence.Entity")) {
            field("id", T("java.lang.Long"), annotations = listOf("jakarta.persistence.Id"))
            field("reference", T("java.lang.String"))
            field("amount", T("double"))
            method("getId", returns = T("java.lang.Long"))
            method("setId") { param("id", T("java.lang.Long")) }
            method("getReference", returns = T("java.lang.String"))
            method("setReference") { param("reference", T("java.lang.String")) }
            method("getAmount", returns = T("double"))
            method("setAmount") { param("amount", T("double")) }
        }

        klass("$pkg.OrderDTO") {
            field("id", T("java.lang.Long"))
            field("reference", T("java.lang.String"))
            field("amount", T("double"))
            method("<init>", returns = T("$pkg.OrderDTO"))
            method("<init>", returns = T("$pkg.OrderDTO")) {
                param("id", T("java.lang.Long"))
                param("reference", T("java.lang.String"))
                param("amount", T("double"))
            }
            method("getId", returns = T("java.lang.Long"))
            method("getReference", returns = T("java.lang.String"))
            method("getAmount", returns = T("double"))
        }

        klass(
            "$pkg.OrderRepository",
            annotations = listOf("org.springframework.stereotype.Repository"),
            isInterface = true,
            interfaces = listOf("org.springframework.data.repository.CrudRepository")
        ) {
            method("findByReference", returns = T("$pkg.OrderEntity")) {
                param("reference", T("java.lang.String"))
            }
        }

        klass("$pkg.OrderService", annotations = listOf("org.springframework.stereotype.Service")) {
            field(
                "repository",
                T("$pkg.OrderRepository"),
                annotations = listOf("org.springframework.beans.factory.annotation.Autowired")
            )
            method(
                "findOrder",
                returns = T("$pkg.OrderDTO", nullable = true),
                body = """
                    OrderEntity entity = repository.findByReference(reference);
                    if (entity == null) { return null; }
                    return new OrderDTO(entity.getId(), entity.getReference(), entity.getAmount());
                """.trimIndent()
            ) {
                param("reference", T("java.lang.String"))
                reads("$pkg.OrderService", "repository")
                calls("$pkg.OrderRepository", "findByReference", "java.lang.String")
                calls("$pkg.OrderEntity", "getId")
                calls("$pkg.OrderEntity", "getReference")
                calls("$pkg.OrderEntity", "getAmount")
            }
        }
    }

    // -- case91 @PostConstruct prioritaire ------------------------------------
    fun case91(): FakeIntrospector = fixture {
        val pkg = "com.testproject.case91"

        klass("$pkg.DiscountEntity", annotations = listOf("jakarta.persistence.Entity")) {
            field("id", T("java.lang.Long"), annotations = listOf("jakarta.persistence.Id"))
            field("code", T("java.lang.String"))
            field("percent", T("double"))
            method("getId", returns = T("java.lang.Long"))
            method("getCode", returns = T("java.lang.String"))
            method("getPercent", returns = T("double"))
            method("setId") { param("id", T("java.lang.Long")) }
            method("setCode") { param("code", T("java.lang.String")) }
            method("setPercent") { param("percent", T("double")) }
        }

        klass("$pkg.DiscountCache") {
            field("cache", Tg("java.util.Map", T("java.lang.String"), T("java.lang.Double")))
            method("warm") {
                param("entities", Tg("java.util.List", T("$pkg.DiscountEntity")))
            }
            method("lookup", returns = T("double")) {
                param("code", T("java.lang.String"))
            }
        }

        klass(
            "$pkg.DiscountRepository",
            annotations = listOf("org.springframework.stereotype.Repository"),
            isInterface = true
        ) {
            method("findAllActive", returns = Tg("java.util.List", T("$pkg.DiscountEntity")))
        }

        klass("$pkg.OrderDTO") {
            field("orderId", T("java.lang.Long"))
            field("finalAmount", T("double"))
            method("<init>", returns = T("$pkg.OrderDTO")) {
                param("orderId", T("java.lang.Long"))
                param("finalAmount", T("double"))
            }
            method("getOrderId", returns = T("java.lang.Long"))
            method("getFinalAmount", returns = T("double"))
        }

        klass("$pkg.OrderService", annotations = listOf("org.springframework.stereotype.Service")) {
            field(
                "repository",
                T("$pkg.DiscountRepository"),
                annotations = listOf("org.springframework.beans.factory.annotation.Autowired")
            )
            field("cache", T("$pkg.DiscountCache"))
            method(
                "init",
                annotations = listOf("jakarta.annotation.PostConstruct"),
                body = "primeCache();"
            ) {
                calls("$pkg.OrderService", "primeCache")
            }
            method(
                "primeCache",
                visibility = "private",
                body = """
                    DiscountCache c = new DiscountCache();
                    List<DiscountEntity> entities = repository.findAllActive();
                    c.warm(entities);
                    this.cache = c;
                """.trimIndent()
            ) {
                reads("$pkg.OrderService", "repository")
                // BLOC 7 a besoin d'un FieldAssignment (pas seulement d'un
                // FieldAccess write=true) pour que SourceCollector détecte
                // primeCache comme MethodInitializer du champ `cache`.
                assigns("$pkg.OrderService", "cache", rhsExpression = "c")
                calls("$pkg.DiscountRepository", "findAllActive")
                calls("$pkg.DiscountCache", "warm", "java.util.List")
            }
            method(
                "calculate",
                returns = T("$pkg.OrderDTO"),
                body = """
                    double percent = cache.lookup(discountCode);
                    double finalAmount = rawAmount * (1.0 - percent / 100.0);
                    return new OrderDTO(orderId, finalAmount);
                """.trimIndent()
            ) {
                param("orderId", T("java.lang.Long"))
                param("discountCode", T("java.lang.String"))
                param("rawAmount", T("double"))
                reads("$pkg.OrderService", "cache")
                calls("$pkg.DiscountCache", "lookup", "java.lang.String")
            }
        }
    }

    // -- case92 méthode publique avec arguments -------------------------------
    fun case92(): FakeIntrospector = fixture {
        val pkg = "com.testproject.case92"

        klass(
            "$pkg.PricingGateway",
            annotations = listOf("org.springframework.stereotype.Component"),
            isInterface = true
        ) {
            method("fetchRate", returns = T("double")) {
                param("region", T("java.lang.String"))
            }
        }

        klass("$pkg.Config") {
            field("timeoutMs", T("int"))
            field("region", T("java.lang.String"))
            field("rate", T("double"))
            method("<init>", returns = T("$pkg.Config")) {
                param("timeoutMs", T("int"))
                param("region", T("java.lang.String"))
            }
            method("getTimeoutMs", returns = T("int"))
            method("getRegion", returns = T("java.lang.String"))
            method("getRate", returns = T("double"))
            method("setRate") { param("rate", T("double")) }
            method("apply", returns = T("double")) { param("amount", T("double")) }
        }

        klass("$pkg.OrderRequest") {
            field("id", T("java.lang.Long"))
            field("rawAmount", T("double"))
            method("<init>", returns = T("$pkg.OrderRequest")) {
                param("id", T("java.lang.Long"))
                param("rawAmount", T("double"))
            }
            method("getId", returns = T("java.lang.Long"))
            method("getRawAmount", returns = T("double"))
        }

        klass("$pkg.OrderDTO") {
            field("id", T("java.lang.Long"))
            field("finalAmount", T("double"))
            field("region", T("java.lang.String"))
            method("<init>", returns = T("$pkg.OrderDTO")) {
                param("id", T("java.lang.Long"))
                param("finalAmount", T("double"))
                param("region", T("java.lang.String"))
            }
            method("getId", returns = T("java.lang.Long"))
            method("getFinalAmount", returns = T("double"))
            method("getRegion", returns = T("java.lang.String"))
        }

        klass("$pkg.OrderService", annotations = listOf("org.springframework.stereotype.Service")) {
            field(
                "pricingGateway",
                T("$pkg.PricingGateway"),
                annotations = listOf("org.springframework.beans.factory.annotation.Autowired")
            )
            field("config", T("$pkg.Config"))
            method(
                "configure",
                body = """
                    Config c = new Config(timeoutMs, region);
                    c.setRate(pricingGateway.fetchRate(region));
                    this.config = c;
                """.trimIndent()
            ) {
                param("timeoutMs", T("int"))
                param("region", T("java.lang.String"))
                reads("$pkg.OrderService", "pricingGateway")
                writes("$pkg.OrderService", "config")
                calls("$pkg.PricingGateway", "fetchRate", "java.lang.String")
                calls("$pkg.Config", "setRate", "double")
            }
            method(
                "calculate",
                returns = T("$pkg.OrderDTO"),
                declaredThrows = listOf("java.lang.IllegalArgumentException"),
                body = """
                    if (request == null) { throw new IllegalArgumentException("request is null"); }
                    double finalAmount = config.apply(request.getRawAmount());
                    return new OrderDTO(request.getId(), finalAmount, config.getRegion());
                """.trimIndent()
            ) {
                param("request", T("$pkg.OrderRequest"))
                reads("$pkg.OrderService", "config")
                calls("$pkg.Config", "apply", "double")
                calls("$pkg.OrderRequest", "getRawAmount")
                calls("$pkg.OrderRequest", "getId")
                calls("$pkg.Config", "getRegion")
            }
        }
    }

    // -- case93 chaîne transitive (cas critique) ------------------------------
    fun case93(): FakeIntrospector = fixture {
        val pkg = "com.testproject.case93"

        klass(
            "$pkg.Loader",
            annotations = listOf("org.springframework.stereotype.Component"),
            isInterface = true
        ) {
            method("load", returns = Tg("java.util.List", T("$pkg.CacheEntry")))
        }

        klass("$pkg.CacheEntry") {
            field("key", T("java.lang.String"))
            field("value", T("java.lang.String"))
            method("<init>", returns = T("$pkg.CacheEntry")) {
                param("key", T("java.lang.String"))
                param("value", T("java.lang.String"))
            }
            method("getKey", returns = T("java.lang.String"))
            method("getValue", returns = T("java.lang.String"))
        }

        klass("$pkg.Cache") {
            field("entries", Tg("java.util.Map", T("java.lang.String"), T("java.lang.String")))
            method("<init>", returns = T("$pkg.Cache")) {
                param("initial", Tg("java.util.List", T("$pkg.CacheEntry")))
            }
            method("get", returns = T("java.lang.String")) {
                param("key", T("java.lang.String"))
            }
        }

        klass("$pkg.OrderRequest") {
            field("id", T("java.lang.Long"))
            field("key", T("java.lang.String"))
            method("<init>", returns = T("$pkg.OrderRequest")) {
                param("id", T("java.lang.Long"))
                param("key", T("java.lang.String"))
            }
            method("getId", returns = T("java.lang.Long"))
            method("getKey", returns = T("java.lang.String"))
        }

        klass("$pkg.OrderDTO") {
            field("id", T("java.lang.Long"))
            field("resolvedValue", T("java.lang.String"))
            method("<init>", returns = T("$pkg.OrderDTO")) {
                param("id", T("java.lang.Long"))
                param("resolvedValue", T("java.lang.String"))
            }
            method("getId", returns = T("java.lang.Long"))
            method("getResolvedValue", returns = T("java.lang.String"))
        }

        klass("$pkg.AbstractCacheService", isAbstract = true) {
            field("cache", T("$pkg.Cache"), visibility = "protected")
            method("warmup", visibility = "protected", body = "// hook")
        }

        klass(
            "$pkg.OrderService",
            annotations = listOf("org.springframework.stereotype.Service"),
            superFqn = "$pkg.AbstractCacheService"
        ) {
            field(
                "loader",
                T("$pkg.Loader"),
                annotations = listOf("org.springframework.beans.factory.annotation.Autowired")
            )
            method("start", body = "startInternal();") {
                calls("$pkg.OrderService", "startInternal")
            }
            method("startInternal", visibility = "private", body = "warmup();") {
                calls("$pkg.OrderService", "warmup")
            }
            method(
                "warmup",
                visibility = "protected",
                annotations = listOf("java.lang.Override"),
                body = "this.cache = buildCache();"
            ) {
                writes("$pkg.AbstractCacheService", "cache")
                calls("$pkg.OrderService", "buildCache")
            }
            method(
                "buildCache",
                returns = T("$pkg.Cache"),
                visibility = "private",
                body = """
                    List<CacheEntry> entries = loader.load();
                    return new Cache(entries);
                """.trimIndent()
            ) {
                reads("$pkg.OrderService", "loader")
                calls("$pkg.Loader", "load")
            }
            method(
                "calculate",
                returns = T("$pkg.OrderDTO"),
                body = """
                    String value = cache.get(request.getKey());
                    return new OrderDTO(request.getId(), value);
                """.trimIndent()
            ) {
                param("request", T("$pkg.OrderRequest"))
                reads("$pkg.AbstractCacheService", "cache")
                calls("$pkg.Cache", "get", "java.lang.String")
                calls("$pkg.OrderRequest", "getKey")
                calls("$pkg.OrderRequest", "getId")
            }
        }

        superChain("$pkg.OrderService", "$pkg.AbstractCacheService")
    }

    // -- case94 auto-init dans methodeCible -----------------------------------
    fun case94(): FakeIntrospector = fixture {
        val pkg = "com.testproject.case94"

        klass(
            "$pkg.PriceProvider",
            annotations = listOf("org.springframework.stereotype.Component"),
            isInterface = true
        ) {
            method("getPrice", returns = T("double")) {
                param("sku", T("java.lang.String"))
            }
        }

        klass("$pkg.Cache") {
            field("entries", Tg("java.util.Map", T("java.lang.String"), T("java.lang.Double")))
            method("has", returns = T("boolean")) { param("sku", T("java.lang.String")) }
            method("get", returns = T("double")) { param("sku", T("java.lang.String")) }
            method("put") {
                param("sku", T("java.lang.String"))
                param("price", T("double"))
            }
        }

        klass("$pkg.OrderDTO") {
            field("sku", T("java.lang.String"))
            field("price", T("double"))
            method("<init>", returns = T("$pkg.OrderDTO")) {
                param("sku", T("java.lang.String"))
                param("price", T("double"))
            }
            method("getSku", returns = T("java.lang.String"))
            method("getPrice", returns = T("double"))
        }

        klass("$pkg.OrderService", annotations = listOf("org.springframework.stereotype.Service")) {
            field(
                "priceProvider",
                T("$pkg.PriceProvider"),
                annotations = listOf("org.springframework.beans.factory.annotation.Autowired")
            )
            field("cache", T("$pkg.Cache"))
            method(
                "primeIfNeeded",
                visibility = "private",
                body = "if (cache == null) { cache = new Cache(); }"
            ) {
                reads("$pkg.OrderService", "cache")
                writes("$pkg.OrderService", "cache")
            }
            method(
                "calculate",
                returns = T("$pkg.OrderDTO"),
                body = """
                    primeIfNeeded();
                    if (!cache.has(sku)) { cache.put(sku, priceProvider.getPrice(sku)); }
                    return new OrderDTO(sku, cache.get(sku));
                """.trimIndent()
            ) {
                param("sku", T("java.lang.String"))
                reads("$pkg.OrderService", "cache")
                reads("$pkg.OrderService", "priceProvider")
                calls("$pkg.OrderService", "primeIfNeeded")
                calls("$pkg.Cache", "has", "java.lang.String")
                calls("$pkg.Cache", "put", "java.lang.String", "double")
                calls("$pkg.PriceProvider", "getPrice", "java.lang.String")
                calls("$pkg.Cache", "get", "java.lang.String")
            }
        }
    }

    // -- case95 UNTESTABLE_AS_IS ----------------------------------------------
    fun case95(): FakeIntrospector = fixture {
        val pkg = "com.testproject.case95"

        klass("$pkg.Config") {
            field("region", T("java.lang.String"))
            method("<init>", returns = T("$pkg.Config")) {
                param("region", T("java.lang.String"))
            }
            method("getRegion", returns = T("java.lang.String"))
        }

        klass("$pkg.Cache") {
            field("config", T("$pkg.Config"))
            field("data", Tg("java.util.Map", T("java.lang.String"), T("java.lang.String")))
            method("<init>", returns = T("$pkg.Cache")) {
                param("config", T("$pkg.Config"))
            }
            method("get", returns = T("java.lang.String")) {
                param("key", T("java.lang.String"))
            }
        }

        klass("$pkg.OrderDTO") {
            field("key", T("java.lang.String"))
            field("value", T("java.lang.String"))
            method("<init>", returns = T("$pkg.OrderDTO")) {
                param("key", T("java.lang.String"))
                param("value", T("java.lang.String"))
            }
            method("getKey", returns = T("java.lang.String"))
            method("getValue", returns = T("java.lang.String"))
        }

        klass("$pkg.OrderService", annotations = listOf("org.springframework.stereotype.Service")) {
            field("cache", T("$pkg.Cache"))
            method(
                "primeCache",
                visibility = "private",
                body = "this.cache = new Cache(c);"
            ) {
                param("c", T("$pkg.Config"))
                writes("$pkg.OrderService", "cache")
            }
            method(
                "calculate",
                returns = T("$pkg.OrderDTO"),
                body = "return new OrderDTO(key, cache.get(key));"
            ) {
                param("key", T("java.lang.String"))
                reads("$pkg.OrderService", "cache")
                calls("$pkg.Cache", "get", "java.lang.String")
            }
        }
    }

    // -- case96 dégradé (cycle + wildcards) -----------------------------------
    fun case96Degraded(): FakeIntrospector = fixture {
        val pkg = "com.testproject.case96_degraded"

        // NodeA<T> ↔ NodeB<T> — référence circulaire générique. Le type
        // paramètre `T` est représenté comme isUnresolvedTypeParameter=true.
        val tParam = ResolvedType(
            rawType = "T",
            fqName = "T",
            isUnresolvedTypeParameter = true
        )

        klass("$pkg.NodeA") {
            field("peer", Tg("$pkg.NodeB", tParam))
            field("payload", tParam)
            method("getPeer", returns = Tg("$pkg.NodeB", tParam))
            method("setPeer") { param("peer", Tg("$pkg.NodeB", tParam)) }
            method("getPayload", returns = tParam)
            method("setPayload") { param("payload", tParam) }
        }

        klass("$pkg.NodeB") {
            field("peer", Tg("$pkg.NodeA", tParam))
            field("payload", tParam)
            method("getPeer", returns = Tg("$pkg.NodeA", tParam))
            method("setPeer") { param("peer", Tg("$pkg.NodeA", tParam)) }
            method("getPayload", returns = tParam)
            method("setPayload") { param("payload", tParam) }
        }

        klass(
            "$pkg.LegacyRepository",
            annotations = listOf("org.springframework.stereotype.Repository"),
            isInterface = true
        ) {
            method("loadRaw", returns = Tg("java.util.Map", Wildcard, Wildcard)) {
                param("tag", T("java.lang.String"))
            }
        }

        klass("$pkg.LegacyDTO") {
            field("tag", T("java.lang.String"))
            field("rawData", Tg("java.util.Map", Wildcard, Wildcard))
            method("<init>", returns = T("$pkg.LegacyDTO")) {
                param("tag", T("java.lang.String"))
                param("rawData", Tg("java.util.Map", Wildcard, Wildcard))
            }
            method("getTag", returns = T("java.lang.String"))
            method("getRawData", returns = Tg("java.util.Map", Wildcard, Wildcard))
        }

        klass("$pkg.LegacyService", annotations = listOf("org.springframework.stereotype.Service")) {
            field(
                "repository",
                T("$pkg.LegacyRepository"),
                annotations = listOf("org.springframework.beans.factory.annotation.Autowired")
            )
            field("rootNode", Tg("$pkg.NodeA", T("java.lang.String")))
            method(
                "process",
                returns = T("$pkg.LegacyDTO"),
                body = """
                    Map<?, ?> raw = repository.loadRaw(tag);
                    if (rootNode != null && rootNode.getPeer() != null) {
                        return new LegacyDTO(tag + ":" + rootNode.getPayload(), raw);
                    }
                    return new LegacyDTO(tag, raw);
                """.trimIndent()
            ) {
                param("tag", T("java.lang.String"))
                reads("$pkg.LegacyService", "repository")
                reads("$pkg.LegacyService", "rootNode")
                calls("$pkg.LegacyRepository", "loadRaw", "java.lang.String")
                calls("$pkg.NodeA", "getPeer")
                calls("$pkg.NodeA", "getPayload")
            }
        }
    }
}
