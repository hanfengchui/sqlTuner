package com.codex.sqltuner.config;

import com.codex.sqltuner.auth.AdminUserController;
import com.codex.sqltuner.auth.AdminUserView;
import com.codex.sqltuner.auth.AuthController;
import com.codex.sqltuner.auth.AuthService;
import com.codex.sqltuner.auth.UserAccount;
import com.codex.sqltuner.auth.UserRole;
import com.codex.sqltuner.common.GlobalExceptionHandler;
import com.codex.sqltuner.conversation.ConversationController;
import com.codex.sqltuner.conversation.ConversationRepository;
import com.codex.sqltuner.tuning.CreateTuningTaskRequest;
import com.codex.sqltuner.tuning.SqlTuningTask;
import com.codex.sqltuner.tuning.TaskEventBroker;
import com.codex.sqltuner.tuning.TuningController;
import com.codex.sqltuner.tuning.TuningHarnessService;
import com.codex.sqltuner.tuning.TuningTaskRepository;
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

import javax.annotation.Resource;
import javax.servlet.http.Cookie;

import java.util.Collections;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        AdminConfigController.class,
        AdminUserController.class,
        AuthController.class,
        ConversationController.class,
        TuningController.class
})
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class SecurityBoundaryTest {
    @Resource
    private MockMvc mockMvc;

    @Resource(name = "sessionAuthenticationFilterRegistration")
    private FilterRegistrationBean<SessionAuthenticationFilter> sessionAuthenticationFilterRegistration;

    @MockBean
    private ModelConfigService modelConfigService;

    @MockBean
    private AdminRuntimeHealthService adminRuntimeHealthService;

    @MockBean
    private AuthService authService;

    @MockBean
    private ConversationRepository conversationRepository;

    @MockBean
    private TuningHarnessService tuningHarnessService;

    @MockBean
    private TuningTaskRepository tuningTaskRepository;

    @MockBean
    private TaskEventBroker taskEventBroker;

    @MockBean
    private QueueProperties queueProperties;

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
    void regularUserCannotReadOrWriteAdministratorConfiguration() throws Exception {
        MockHttpSession session = session(UserRole.USER);

        mockMvc.perform(get("/api/admin/model-config").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        CsrfRequest csrf = csrfRequest();
        mockMvc.perform(post("/api/admin/model-config")
                        .session(session)
                        .cookie(csrf.cookie)
                        .header(csrf.token.getHeaderName(), csrf.token.getToken())
                        .contentType("application/json")
                        .content("{\"provider\":\"openai-compatible\",\"model\":\"forbidden-model\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void administratorCanReadAndWriteModelConfiguration() throws Exception {
        MockHttpSession session = session(UserRole.ADMIN);
        ModelConfigView view = new ModelConfigView(
                "openai-compatible",
                "https://example.invalid/v1",
                "configured-model",
                null,
                60000,
                true,
                "ready");
        when(modelConfigService.view()).thenReturn(view);
        when(modelConfigService.update(any(ModelConfigUpdateRequest.class))).thenReturn(view);

        mockMvc.perform(get("/api/admin/model-config").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.model").value("configured-model"));

        CsrfRequest csrf = csrfRequest();
        mockMvc.perform(post("/api/admin/model-config")
                        .session(session)
                        .cookie(csrf.cookie)
                        .header(csrf.token.getHeaderName(), csrf.token.getToken())
                        .contentType("application/json")
                        .content("{\"provider\":\"openai-compatible\",\"model\":\"configured-model\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.model").value("configured-model"));
    }

    @Test
    void administratorCanListUsersButRegularUserCannot() throws Exception {
        MockHttpSession adminSession = session(UserRole.ADMIN);
        when(authService.listUsers()).thenReturn(Collections.singletonList(
                new AdminUserView(2L, "user", "User", UserRole.USER, true)));

        mockMvc.perform(get("/api/admin/users").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].username").value("user"));

        mockMvc.perform(get("/api/admin/users").session(session(UserRole.USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void regularUserCanUseConversationAndTuningApis() throws Exception {
        MockHttpSession session = session(UserRole.USER);
        when(conversationRepository.listByUser(2L)).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/conversations").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        SqlTuningTask task = new SqlTuningTask();
        task.setId(17L);
        task.setUserId(2L);
        task.setConversationId(9L);
        when(queueProperties.getMaxQueuedGlobal()).thenReturn(100);
        when(queueProperties.getMaxQueuedPerUser()).thenReturn(10);
        when(tuningHarnessService.createTask(eq(2L), any(CreateTuningTaskRequest.class), eq("user-security-test")))
                .thenReturn(task);
        when(tuningTaskRepository.queuePosition(task)).thenReturn(1);
        when(tuningTaskRepository.publicView(task)).thenReturn(task);

        CsrfRequest csrf = csrfRequest();
        mockMvc.perform(post("/api/tuning/tasks")
                        .session(session)
                        .cookie(csrf.cookie)
                        .header(csrf.token.getHeaderName(), csrf.token.getToken())
                        .header("Idempotency-Key", "user-security-test")
                        .contentType("application/json")
                        .content("{\"dbDialect\":\"OceanBase MySQL\",\"sqlText\":\"SELECT id FROM orders WHERE id = 1\",\"deepAnalysis\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(17))
                .andExpect(jsonPath("$.data.userId").value(2));
    }

    @Test
    void loginSessionCanAccessProtectedEndpointOnTheNextRequest() throws Exception {
        UserAccount admin = new UserAccount(1L, "admin", "Admin", "", UserRole.ADMIN);
        when(authService.login(eq("admin"), eq("strong-password"), any()))
                .thenReturn(admin);
        when(authService.requireActiveAccount(1L)).thenReturn(admin);

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

    private MockHttpSession session(UserRole role) {
        MockHttpSession session = new MockHttpSession();
        long userId = role == UserRole.ADMIN ? 1L : 2L;
        UserAccount account = new UserAccount(userId, role.name().toLowerCase(), role.name(), null, role);
        session.setAttribute(AuthService.SESSION_USER, account);
        when(authService.requireActiveAccount(userId)).thenReturn(account);
        return session;
    }

    private CsrfRequest csrfRequest() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        CsrfToken token = (CsrfToken) result.getRequest().getAttribute(CsrfToken.class.getName());
        return new CsrfRequest(token, result.getResponse().getCookie("XSRF-TOKEN"));
    }

    private static final class CsrfRequest {
        private final CsrfToken token;
        private final Cookie cookie;

        private CsrfRequest(CsrfToken token, Cookie cookie) {
            this.token = token;
            this.cookie = cookie;
        }
    }
}
