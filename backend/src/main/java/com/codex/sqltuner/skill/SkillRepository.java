package com.codex.sqltuner.skill;

import com.codex.sqltuner.common.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class SkillRepository {
    private static final Logger log = LoggerFactory.getLogger(SkillRepository.class);
    public static final String DEFAULT_SKILL_NAME = "oceanbase-sql-tuning";
    private final JdbcTemplate jdbcTemplate;

    public SkillRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<SkillVersion> list() {
        return jdbcTemplate.query("SELECT * FROM skill_versions ORDER BY name ASC, version DESC", mapper());
    }

    public SkillVersion activeDefault() {
        List<SkillVersion> skills = jdbcTemplate.query(
                "SELECT * FROM skill_versions WHERE name = ? AND enabled = TRUE ORDER BY version DESC LIMIT 1",
                mapper(), DEFAULT_SKILL_NAME);
        if (skills.isEmpty()) {
            throw new NotFoundException("默认 SQL 调优技能不存在");
        }
        SkillVersion skill = skills.get(0);
        SkillPromptPolicy.requireValid(skill.getName(), skill.getContent());
        log.info("activeDefaultSkill result 结果: skillName: {}, version: {}", skill.getName(), skill.getVersion());
        return skill;
    }

    @Transactional
    public SkillVersion save(final String name, final String content) {
        SkillPromptPolicy.requireValid(name, content);
        Integer current = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM skill_versions WHERE name = ?",
                Integer.class, name);
        final int nextVersion = current == null ? 1 : current + 1;
        final LocalDateTime now = LocalDateTime.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(new org.springframework.jdbc.core.PreparedStatementCreator() {
            @Override
            public PreparedStatement createPreparedStatement(Connection con) throws java.sql.SQLException {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO skill_versions(name, version, content, enabled, updated_at) VALUES (?, ?, ?, TRUE, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, name);
                ps.setInt(2, nextVersion);
                ps.setString(3, content);
                ps.setTimestamp(4, Timestamp.valueOf(now));
                return ps;
            }
        }, keyHolder);
        Number key = keyHolder.getKey();
        SkillVersion saved = new SkillVersion(key == null ? null : key.longValue(), name, nextVersion, content, true, now);
        log.info("saveSkill result 结果: skillName: {}, version: {}", name, saved.getVersion());
        return saved;
    }

    public void setEnabled(final String name, final boolean enabled) {
        int updated = jdbcTemplate.update("UPDATE skill_versions SET enabled = ?, updated_at = ? WHERE name = ?",
                enabled, Timestamp.valueOf(LocalDateTime.now()), name);
        if (updated == 0) {
            throw new NotFoundException("技能不存在");
        }
    }

    private RowMapper<SkillVersion> mapper() {
        return new RowMapper<SkillVersion>() {
            @Override
            public SkillVersion mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
                return new SkillVersion(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getInt("version"),
                        rs.getString("content"),
                        rs.getBoolean("enabled"),
                        rs.getTimestamp("updated_at").toLocalDateTime());
            }
        };
    }
}
