package com.codex.sqltuner.tuning;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelStreamProjectorTest {
    private final ModelStreamProjector projector = new ModelStreamProjector(new ObjectMapper());

    @Test
    void suppressesTheWholeUnvalidatedDraftWhenNarrativeContainsExecutableSql() {
        String modelOutput = "{"
                + "\"analysisNarrative\":{\"conclusion\":\"先确认访问路径。\","
                + "\"sections\":[{\"kind\":\"ACTION\",\"title\":\"建议\",\"body\":\"先补齐 EXPLAIN。\\nCREATE INDEX idx_orders ON orders(id);\\n再评估写入成本。\"}]},"
                + "\"indexCandidates\":[{\"title\":\"候选索引标题不应泄露\",\"body\":\"候选正文不应泄露\",\"ddl\":\"CREATE INDEX should_not_leak ON t(c)\"}]"
                + "}";

        String draft = projector.project(modelOutput);

        assertThat(draft).isEmpty();
    }

    @Test
    void suppressesCommonDdlVariantsThatMustNeverReachTheBrowser() {
        assertThat(projector.project(narrative("CREATE UNIQUE INDEX idx_u ON t(c)"))).isEmpty();
        assertThat(projector.project(narrative("CREATE BITMAP INDEX idx_b ON t(c)"))).isEmpty();
        assertThat(projector.project(narrative("CREATE OR REPLACE VIEW v AS SELECT c FROM t"))).isEmpty();
        assertThat(projector.project(narrative("CREATE FULLTEXT INDEX idx_f ON t(c)"))).isEmpty();
        assertThat(projector.project(narrative("CREATE SPATIAL INDEX idx_s ON t(c)"))).isEmpty();
        assertThat(projector.project(narrative("CREATE /*comment*/ UNIQUE INDEX idx_c ON t(c)"))).isEmpty();
        assertThat(projector.project(narrative("REPLACE INTO t(id) VALUES (1)"))).isEmpty();
    }

    @Test
    void projectsNarrativeProseWhenItContainsNoExecutableSql() {
        String draft = projector.project(narrative("先确认访问路径，再评估写入成本。"));

        assertThat(draft).contains("先确认访问路径");
        assertThat(draft).contains("建议");
    }

    @Test
    void ignoresRawJsonWithoutNarrative() {
        assertThat(projector.project("{\"indexCandidates\":[{\"ddl\":\"CREATE INDEX x ON t(c)\"}]}")).isEmpty();
    }

    private String narrative(String body) {
        return "{\"analysisNarrative\":{\"conclusion\":\"先确认访问路径。\","
                + "\"sections\":[{\"kind\":\"ACTION\",\"title\":\"建议\",\"body\":\"" + body + "\"}]}}";
    }
}
