package com.codex.sqltuner.storage;

import com.codex.sqltuner.auth.UserRole;
import com.codex.sqltuner.llm.LlmProperties;
import com.codex.sqltuner.skill.SkillRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import javax.annotation.PostConstruct;
import java.sql.Timestamp;
import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(name = "app.legacy-import.enabled", havingValue = "false", matchIfMissing = true)
public class DatabaseBootstrap {
    private static final Logger log = LoggerFactory.getLogger(DatabaseBootstrap.class);
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final LlmProperties llmProperties;
    private final CryptoSupport cryptoSupport;

    public DatabaseBootstrap(JdbcTemplate jdbcTemplate,
                             PasswordEncoder passwordEncoder,
                             LlmProperties llmProperties,
                             CryptoSupport cryptoSupport) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.llmProperties = llmProperties;
        this.cryptoSupport = cryptoSupport;
    }

    @PostConstruct
    public void init() {
        ensureUsers();
        ensureModelConfig();
        loadRuntimeModelConfig();
        ensureDefaultSkill();
    }

    private void ensureUsers() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        if (count != null && count > 0) {
            return;
        }
        String adminPassword = requiredStrongPassword("SQL_TUNER_ADMIN_PASSWORD");
        String userPassword = requiredStrongPassword("SQL_TUNER_USER_PASSWORD");
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO users(id, username, display_name, password_hash, role, enabled, created_at, updated_at) VALUES (1, 'admin', '管理员', ?, ?, TRUE, ?, ?)",
                passwordEncoder.encode(adminPassword), UserRole.ADMIN.name(), Timestamp.valueOf(now), Timestamp.valueOf(now));
        jdbcTemplate.update(
                "INSERT INTO users(id, username, display_name, password_hash, role, enabled, created_at, updated_at) VALUES (2, 'user', '业务用户', ?, ?, TRUE, ?, ?)",
                passwordEncoder.encode(userPassword), UserRole.USER.name(), Timestamp.valueOf(now), Timestamp.valueOf(now));
        log.info("databaseBootstrap result 结果: 初始化用户完成");
    }

    private String requiredStrongPassword(String envName) {
        String value = System.getenv(envName);
        if (value == null || value.trim().length() < 12) {
            throw new IllegalStateException(envName + " 必须配置至少 12 位强密码，系统不再提供默认弱口令");
        }
        return value.trim();
    }

    private void ensureModelConfig() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM model_config WHERE id = 1", Integer.class);
        if (count != null && count > 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        String apiKey = llmProperties.getApiKey();
        jdbcTemplate.update(
                "INSERT INTO model_config(id, provider, base_url, model, encrypted_api_key, timeout_ms, updated_at) VALUES (1, ?, ?, ?, ?, ?, ?)",
                llmProperties.getProvider(),
                llmProperties.getBaseUrl(),
                llmProperties.getModel(),
                apiKey == null || apiKey.trim().isEmpty() ? null : cryptoSupport.encrypt(apiKey.trim()),
                llmProperties.getTimeoutMs(),
                Timestamp.valueOf(now));
    }

    private void loadRuntimeModelConfig() {
        ModelConfigRecord record = jdbcTemplate.queryForObject(
                "SELECT provider, base_url, model, encrypted_api_key, timeout_ms FROM model_config WHERE id = 1",
                (rs, rowNum) -> {
                    ModelConfigRecord value = new ModelConfigRecord(
                            rs.getString("provider"),
                            rs.getString("base_url"),
                            rs.getString("model"),
                            rs.getInt("timeout_ms"));
                    String storedKey = rs.getString("encrypted_api_key");
                    String decrypted = storedKey == null ? null : cryptoSupport.decrypt(storedKey);
                    if (storedKey != null && cryptoSupport.isEncrypted(storedKey) && decrypted == null) {
                        throw new IllegalStateException("SQL_TUNER_DATA_KEY 无法解密数据库中的模型 API Key");
                    }
                    value.setApiKey(decrypted);
                    return value;
                });
        llmProperties.apply(record);
        log.info("databaseBootstrap result 结果: 已加载数据库模型配置, provider: {}, model: {}",
                record.getProvider(), record.getModel());
    }

    private void ensureDefaultSkill() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM skill_versions WHERE name = ?",
                Integer.class, SkillRepository.DEFAULT_SKILL_NAME);
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.update(
                "INSERT INTO skill_versions(name, version, content, enabled, updated_at) VALUES (?, 1, ?, TRUE, ?)",
                SkillRepository.DEFAULT_SKILL_NAME,
                defaultSkillContent(),
                Timestamp.valueOf(LocalDateTime.now()));
    }

    private String defaultSkillContent() {
        return "# OceanBase SQL 调优技能\n\n"
                + "你是面向 OceanBase MySQL 与 OceanBase Oracle 兼容模式的 SQL 调优专家。\n\n"
                + "## 必须遵守\n"
                + "- 不要编造表结构、索引、执行计划或数据量。\n"
                + "- 优先基于确定性规则、EXPLAIN、索引信息给建议。\n"
                + "- 信息不足时输出缺失信息，不生成确定性 DDL。\n"
                + "- 输出必须是 JSON 对象，并引用已有证据。\n";
    }
}
