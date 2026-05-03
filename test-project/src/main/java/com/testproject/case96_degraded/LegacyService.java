package com.testproject.case96_degraded;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class LegacyService {

    @Autowired
    private LegacyRepository repository;

    private NodeA<String> rootNode;

    // @TestTarget
    // Cas dégradé : type wildcard imbriqué (Map<?, ?>) + utilisation d'une
    // référence circulaire NodeA↔NodeB. La stratégie doit produire un
    // ContextTree exploitable même si certains types restent non résolus.
    public LegacyDTO process(String tag) {
        Map<?, ?> raw = repository.loadRaw(tag);
        if (rootNode != null && rootNode.getPeer() != null) {
            return new LegacyDTO(tag + ":" + rootNode.getPayload(), raw);
        }
        return new LegacyDTO(tag, raw);
    }
}
