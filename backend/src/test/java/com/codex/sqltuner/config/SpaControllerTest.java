package com.codex.sqltuner.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpaControllerTest {
    @Test
    void redirectsRemovedTaskDetailRouteToConversation() {
        assertThat(new SpaController().legacyTaskRoute()).isEqualTo("redirect:/chat");
    }
}
