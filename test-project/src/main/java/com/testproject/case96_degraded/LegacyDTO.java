package com.testproject.case96_degraded;

import java.util.Map;

public class LegacyDTO {
    private final String tag;
    private final Map<?, ?> rawData;

    public LegacyDTO(String tag, Map<?, ?> rawData) {
        this.tag = tag;
        this.rawData = rawData;
    }

    public String getTag() { return tag; }
    public Map<?, ?> getRawData() { return rawData; }
}
