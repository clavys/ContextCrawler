package com.testproject.case93;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public interface Loader {
    List<CacheEntry> load();
}
