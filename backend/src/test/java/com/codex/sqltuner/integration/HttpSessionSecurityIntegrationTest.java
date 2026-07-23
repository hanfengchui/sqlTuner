package com.codex.sqltuner.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.codex.sqltuner.tuning.TuningQueueWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:http_session_security;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.locations=classpath:db/migration",
        "spring.task.scheduling.enabled=false",
        "app.retention.enabled=false",
        "llm.provider=mock",
        "server.servlet.session.cookie.secure=false"
})
class HttpSessionSecurityIntegrationTest {
    private static final String USER_PASSWORD = "cookie-security-password";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private com.codex.sqltuner.storage.DatabaseBootstrap databaseBootstrap;

    @MockBean
    private TuningQueueWorker scheduledQueueWorker;

    @BeforeEach
    void seedUser() {
        jdbcTemplate.update("DELETE FROM users");
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO users(id, username, display_name, password_hash, role, enabled, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'USER', TRUE, ?, ?)",
                99L,
                "cookie-user",
                "Cookie User",
                passwordEncoder.encode(USER_PASSWORD),
                Timestamp.valueOf(now),
                Timestamp.valueOf(now));
    }

    @Test
    void loggedInRegularUserReceivesForbiddenRatherThanUnauthorizedForAdminEndpoint() throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpResult csrf = request("GET", "/api/auth/csrf", null, null, cookies);
        assertThat(csrf.status).isEqualTo(200);
        String csrfToken = objectMapper.readTree(csrf.body).path("data").path("token").asText();
        assertThat(csrfToken).isNotBlank();
        assertThat(cookies.getCookieStore().getCookies())
                .extracting("name")
                .contains("XSRF-TOKEN");

        String loginBody = objectMapper.writeValueAsString(new LoginBody("cookie-user", USER_PASSWORD));
        HttpResult login = request("POST", "/api/auth/login", loginBody, csrfToken, cookies);
        assertThat(login.status).isEqualTo(200);
        assertThat(cookies.getCookieStore().getCookies())
                .extracting("name")
                .contains("JSESSIONID");

        HttpResult me = request("GET", "/api/auth/me", null, null, cookies);
        assertThat(me.status).isEqualTo(200);
        JsonNode meData = objectMapper.readTree(me.body).path("data");
        assertThat(meData.path("role").asText()).isEqualTo("USER");

        HttpResult admin = request("GET", "/api/admin/users", null, null, cookies);
        assertThat(admin.status).isEqualTo(403);
        assertThat(objectMapper.readTree(admin.body).path("code").asText()).isEqualTo("FORBIDDEN");
    }

    private HttpResult request(String method,
                               String path,
                               String body,
                               String csrfToken,
                               CookieManager cookieManager) throws IOException {
        URI uri = URI.create("http://127.0.0.1:" + port + path);
        HttpURLConnection connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
        connection.setRequestMethod(method);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", MediaType.APPLICATION_JSON_VALUE);
        for (Map.Entry<String, List<String>> entry : cookieManager.get(uri, Collections.<String, List<String>>emptyMap()).entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            for (String value : entry.getValue()) {
                if (value != null && !value.trim().isEmpty()) {
                    connection.addRequestProperty(entry.getKey(), value);
                }
            }
        }
        if (csrfToken != null) {
            connection.setRequestProperty("X-XSRF-TOKEN", csrfToken);
        }
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            connection.setFixedLengthStreamingMode(bytes.length);
            connection.getOutputStream().write(bytes);
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String responseBody = read(stream);
        cookieManager.put(uri, connection.getHeaderFields());
        return new HttpResult(status, responseBody);
    }

    private String read(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static final class LoginBody {
        private final String username;
        private final String password;

        private LoginBody(String username, String password) {
            this.username = username;
            this.password = password;
        }

        public String getUsername() {
            return username;
        }

        public String getPassword() {
            return password;
        }
    }

    private static final class HttpResult {
        private final int status;
        private final String body;

        private HttpResult(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }
}
