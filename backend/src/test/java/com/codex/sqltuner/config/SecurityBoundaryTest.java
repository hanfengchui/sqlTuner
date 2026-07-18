package com.codex.sqltuner.config;

import com.codex.sqltuner.auth.AuthController;
import com.codex.sqltuner.auth.AuthService;
import com.codex.sqltuner.auth.UserAccount;
import com.codex.sqltuner.auth.UserRole;
import com.codex.sqltuner.common.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Properties;

import javax.annotation.Resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
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

    @Resource(name = "sessionAuthenticationFilterRegistration")
    private FilterRegistrationBean<SessionAuthenticationFilter> sessionAuthenticationFilterRegistration;

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
    void sessionAuthenticationFilterRunsOnlyInsideSpringSecurityChain() {
        assertThat(sessionAuthenticationFilterRegistration.isEnabled()).isFalse();
    }

    @Test
    void unsafeAdminWriteWithoutCsrfTokenIsRejectedBeforeController() throws Exception {
        mockMvc.perform(post("/api/admin/model-config")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void loginSessionCanAccessProtectedEndpointOnTheNextRequest() throws Exception {
        when(authService.login("admin", "strong-password"))
                .thenReturn(new UserAccount(1L, "admin", "Admin", "", UserRole.ADMIN));

        MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        CsrfToken token = (CsrfToken) csrfResult.getRequest()
                .getAttribute(CsrfToken.class.getName());

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .cookie(csrfResult.getResponse().getCookie("XSRF-TOKEN"))
                        .header(token.getHeaderName(), token.getToken())
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"strong-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("admin"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();

        mockMvc.perform(get("/api/admin/model-config").session(session))
                .andExpect(status().isOk());
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
