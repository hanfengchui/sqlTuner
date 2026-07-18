package com.codex.sqltuner.conversation;

import com.codex.sqltuner.common.NotFoundException;
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
public class ConversationRepository {
    private final JdbcTemplate jdbcTemplate;

    public ConversationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Conversation create(final Long userId, String title) {
        final String safeTitle = title == null || title.trim().isEmpty() ? "新建调优" : title.trim();
        final LocalDateTime now = LocalDateTime.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(new org.springframework.jdbc.core.PreparedStatementCreator() {
            @Override
            public PreparedStatement createPreparedStatement(Connection con) throws java.sql.SQLException {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO conversations(user_id, title, created_at, updated_at) VALUES (?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, userId);
                ps.setString(2, safeTitle);
                ps.setTimestamp(3, Timestamp.valueOf(now));
                ps.setTimestamp(4, Timestamp.valueOf(now));
                return ps;
            }
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("创建会话失败: 未返回会话 ID");
        }
        return new Conversation(key.longValue(), userId, safeTitle, now, now);
    }

    public List<Conversation> listByUser(Long userId) {
        return jdbcTemplate.query(
                "SELECT * FROM conversations WHERE user_id = ? ORDER BY updated_at DESC",
                conversationMapper(), userId);
    }

    public Conversation getForUser(Long id, Long userId) {
        return getForUser(id, userId, false);
    }

    public Conversation getForUserForUpdate(Long id, Long userId) {
        return getForUser(id, userId, true);
    }

    private Conversation getForUser(Long id, Long userId, boolean forUpdate) {
        List<Conversation> rows = jdbcTemplate.query(
                "SELECT * FROM conversations WHERE id = ? AND user_id = ?" + (forUpdate ? " FOR UPDATE" : ""),
                conversationMapper(), id, userId);
        if (rows.isEmpty()) {
            throw new NotFoundException("会话不存在");
        }
        return rows.get(0);
    }

    public Message addMessage(final Long conversationId, final MessageRole role, final String content, final Long taskId) {
        final LocalDateTime now = LocalDateTime.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(new org.springframework.jdbc.core.PreparedStatementCreator() {
            @Override
            public PreparedStatement createPreparedStatement(Connection con) throws java.sql.SQLException {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO messages(conversation_id, role, content, task_id, created_at) VALUES (?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, conversationId);
                ps.setString(2, role.name());
                ps.setString(3, content == null ? "" : content);
                if (taskId == null) {
                    ps.setNull(4, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(4, taskId);
                }
                ps.setTimestamp(5, Timestamp.valueOf(now));
                return ps;
            }
        }, keyHolder);
        if (role == MessageRole.USER) {
            jdbcTemplate.update(
                    "UPDATE conversations SET title = CASE WHEN title IS NULL OR title = '新建调优' THEN ? ELSE title END, updated_at = ? WHERE id = ?",
                    makeTitle(content), Timestamp.valueOf(now), conversationId);
        } else {
            jdbcTemplate.update("UPDATE conversations SET updated_at = ? WHERE id = ?", Timestamp.valueOf(now), conversationId);
        }
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("创建消息失败: 未返回消息 ID");
        }
        return new Message(key.longValue(), conversationId, role, content, taskId, now);
    }

    public List<Message> listMessages(final Long conversationId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM conversations WHERE id = ?", Integer.class, conversationId);
        if (count == null || count == 0) {
            throw new NotFoundException("会话不存在");
        }
        return jdbcTemplate.query(
                "SELECT * FROM messages WHERE conversation_id = ? ORDER BY created_at ASC, id ASC",
                messageMapper(), conversationId);
    }

    @Transactional
    public void deleteForUser(final Long id, final Long userId) {
        Conversation conversation = getForUser(id, userId);
        deleteConversation(conversation.getId());
    }

    @Transactional
    public int deleteExpiredTerminalConversations(LocalDateTime cutoff, int batchSize) {
        if (cutoff == null || batchSize <= 0) {
            return 0;
        }
        List<Long> conversationIds = jdbcTemplate.queryForList(
                "SELECT c.id FROM conversations c "
                        + "WHERE c.updated_at < ? "
                        + "AND NOT EXISTS (SELECT 1 FROM tuning_tasks t "
                        + "WHERE t.conversation_id = c.id AND t.status NOT IN ('DONE', 'FAILED')) "
                        + "ORDER BY c.updated_at ASC, c.id ASC LIMIT ? FOR UPDATE",
                Long.class, Timestamp.valueOf(cutoff), batchSize);
        for (Long conversationId : conversationIds) {
            if (conversationId != null) {
                deleteConversation(conversationId);
            }
        }
        return conversationIds.size();
    }

    public void restore(final List<Conversation> conversations, final List<Message> messages) {
        jdbcTemplate.update("DELETE FROM messages");
        jdbcTemplate.update("DELETE FROM conversations");
        if (conversations != null) {
            for (Conversation conversation : conversations) {
                jdbcTemplate.update(
                        "INSERT INTO conversations(id, user_id, title, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                        conversation.getId(), conversation.getUserId(), conversation.getTitle(),
                        Timestamp.valueOf(conversation.getCreatedAt()), Timestamp.valueOf(conversation.getUpdatedAt()));
            }
        }
        if (messages != null) {
            for (Message message : messages) {
                jdbcTemplate.update(
                        "INSERT INTO messages(id, conversation_id, role, content, task_id, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                        message.getId(), message.getConversationId(), message.getRole().name(), message.getContent(),
                        message.getTaskId(), Timestamp.valueOf(message.getCreatedAt()));
            }
        }
    }

    private String makeTitle(String content) {
        if (content == null || content.trim().isEmpty()) {
            return "新建调优";
        }
        String normalized = content.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() > 32 ? normalized.substring(0, 32) : normalized;
    }

    private void deleteConversation(Long conversationId) {
        jdbcTemplate.update("DELETE FROM task_input_images WHERE task_id IN (SELECT id FROM tuning_tasks WHERE conversation_id = ?)", conversationId);
        jdbcTemplate.update("DELETE FROM task_artifacts WHERE task_id IN (SELECT id FROM tuning_tasks WHERE conversation_id = ?)", conversationId);
        jdbcTemplate.update("DELETE FROM tuning_tasks WHERE conversation_id = ?", conversationId);
        jdbcTemplate.update("DELETE FROM messages WHERE conversation_id = ?", conversationId);
        jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", conversationId);
    }

    private RowMapper<Conversation> conversationMapper() {
        return new RowMapper<Conversation>() {
            @Override
            public Conversation mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
                return new Conversation(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        rs.getString("title"),
                        rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getTimestamp("updated_at").toLocalDateTime());
            }
        };
    }

    private RowMapper<Message> messageMapper() {
        return new RowMapper<Message>() {
            @Override
            public Message mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
                return new Message(
                        rs.getLong("id"),
                        rs.getLong("conversation_id"),
                        MessageRole.valueOf(rs.getString("role")),
                        rs.getString("content"),
                        rs.getObject("task_id") == null ? null : rs.getLong("task_id"),
                        rs.getTimestamp("created_at").toLocalDateTime());
            }
        };
    }
}
