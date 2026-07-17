package com.codex.sqltuner.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcJsonSupportTest {
    private final JdbcJsonSupport support = new JdbcJsonSupport(new ObjectMapper());

    @Test
    void artifactPayloadAcceptsMysqlUnquotedJsonStringScalar() {
        String payload = "dbDialect=OceanBase MySQL, sqlLength=149";

        assertThat(support.readArtifactPayload(payload)).isEqualTo(payload);
    }

    @Test
    void artifactPayloadStillParsesJsonObjectsAndQuotedStrings() {
        Object object = support.readArtifactPayload("{\"ok\":true}");

        assertThat(object).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) object).get("ok")).isEqualTo(Boolean.TRUE);
        assertThat(support.readArtifactPayload("\"plain text\"")).isEqualTo("plain text");
    }
}
