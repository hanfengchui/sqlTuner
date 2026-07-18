package com.codex.sqltuner.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpaControllerTest {
    @Test
    void redirectsRemovedTaskDetailRouteToConversation() {
        assertThat(new SpaController().legacyTaskRoute().getStatusCodeValue()).isEqualTo(302);
        assertThat(new SpaController().legacyTaskRoute().getHeaders().getLocation()).isEqualTo(java.net.URI.create("/chat"));
    }
}
