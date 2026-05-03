package com.testproject.case96_degraded;

import org.springframework.stereotype.Repository;

import java.util.Map;

@Repository
public interface LegacyRepository {
    // Type non bornable côté usage — PSI doit accepter Map<?, ?> et fallback
    // sur Object pour les wildcards (STRATEGIE.md §3.5 + §8bis.1).
    Map<?, ?> loadRaw(String tag);
}
