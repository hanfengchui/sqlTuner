package com.codex.sqltuner.migration;

import java.util.LinkedHashMap;
import java.util.Map;

public class LegacyImportResult {
    private final String sourceSha256;
    private final boolean dryRun;
    private final boolean noOp;
    private final Map<String, Long> counts;

    public LegacyImportResult(String sourceSha256, boolean dryRun, boolean noOp, Map<String, Long> counts) {
        this.sourceSha256 = sourceSha256;
        this.dryRun = dryRun;
        this.noOp = noOp;
        this.counts = new LinkedHashMap<String, Long>(counts);
    }

    public String getSourceSha256() {
        return sourceSha256;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public boolean isNoOp() {
        return noOp;
    }

    public Map<String, Long> getCounts() {
        return new LinkedHashMap<String, Long>(counts);
    }
}
