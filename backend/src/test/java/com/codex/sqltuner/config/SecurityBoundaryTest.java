package com.codex.sqltuner.config;

import com.codex.sqltuner.auth.AuthController;
import com.codex.sqltuner.auth.AuthService;
import com.codex.sqltuner.common.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Properties;

import javax.annotation.Resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AdminConfigController.class, AuthController.class})
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class SecurityBoundaryTest {
    @Resource
    private MockMvc mockMvc;

    @MockBean
    private ModelConfigService modelConfigService;

    @MockBean
    private AuthService authService;

    @Test
    void unauthenticatedAdminReadIsRejected() throws Exception {
        mockMvc.perform(get("/api/admin/model-config"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void unsafeAdminWriteWithoutCsrfTokenIsRejectedBeforeController() throws Exception {
        mockMvc.perform(post("/api/admin/model-config")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void csrfEndpointExposesTokenCookieForSameOriginWrites() throws Exception {
        mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.headerName").value("X-XSRF-TOKEN"))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(cookie().exists("XSRF-TOKEN"));
    }

    @Test
    void sessionCookieDefaultsAreHttpOnlyAndSameSiteLax() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();

        assertThat(properties).isNotNull();
        assertThat(properties.getProperty("server.servlet.session.cookie.http-only")).isEqualTo("true");
        assertThat(properties.getProperty("server.servlet.session.cookie.same-site")).isEqualTo("lax");
    }
}
