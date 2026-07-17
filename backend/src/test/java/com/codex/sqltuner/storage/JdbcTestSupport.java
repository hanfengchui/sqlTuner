package com.codex.sqltuner.storage;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

public final class JdbcTestSupport {
    private JdbcTestSupport() {
    }

    public static JdbcTemplate jdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:sqltuner_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        migrate(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        seedUsers(jdbcTemplate);
        seedModel(jdbcTemplate);
        seedSkill(jdbcTemplate);
        return jdbcTemplate;
    }

    private static void migrate(DataSource dataSource) {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    private static void seedUsers(JdbcTemplate jdbcTemplate) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO users(id, username, display_name, password_hash, role, enabled, created_at, updated_at) VALUES (1, 'admin', '管理员', ?, 'ADMIN', TRUE, ?, ?)",
                encoder.encode("admin-test-password"), Timestamp.valueOf(now), Timestamp.valueOf(now));
        jdbcTemplate.update(
                "INSERT INTO users(id, username, display_name, password_hash, role, enabled, created_at, updated_at) VALUES (2, 'user', '业务用户', ?, 'USER', TRUE, ?, ?)",
                encoder.encode("user-test-password"), Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    private static void seedModel(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(
                "INSERT INTO model_config(id, provider, base_url, model, vision_model, encrypted_api_key, timeout_ms, updated_at) VALUES (1, 'mock', '', 'mock', 'mock', NULL, 30000, ?)",
                Timestamp.valueOf(LocalDateTime.now()));
    }

    private static void seedSkill(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(
                "INSERT INTO skill_versions(name, version, content, enabled, updated_at) VALUES ('oceanbase-sql-tuning', 1, 'test skill', TRUE, ?)",
                Timestamp.valueOf(LocalDateTime.now()));
    }
}
