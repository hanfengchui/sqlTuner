package com.codex.sqltuner.tuning.inputimage;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class InputImageRepository {
    private final JdbcTemplate jdbcTemplate;

    public InputImageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveAll(Long taskId, List<TaskInputImage> images) {
        if (images == null || images.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (TaskInputImage image : images) {
            image.setTaskId(taskId);
            if (isSameExisting(taskId, image)) {
                continue;
            }
            try {
                jdbcTemplate.update(
                        "INSERT INTO task_input_images(task_id, image_order, file_name, media_type, byte_size, sha256, image_data, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        taskId,
                        image.getImageOrder(),
                        image.getFileName(),
                        image.getMediaType(),
                        image.getByteSize(),
                        image.getSha256(),
                        image.getImageData(),
                        Timestamp.valueOf(now));
            } catch (DuplicateKeyException e) {
                if (!isSameExisting(taskId, image)) {
                    throw new IllegalStateException("同一任务图片序号已存在不同内容，拒绝覆盖");
                }
            }
        }
    }

    private boolean isSameExisting(Long taskId, TaskInputImage image) {
        List<String> hashes = jdbcTemplate.queryForList(
                "SELECT sha256 FROM task_input_images WHERE task_id = ? AND image_order = ?",
                String.class, taskId, image.getImageOrder());
        if (hashes.isEmpty()) {
            return false;
        }
        String existing = hashes.get(0);
        if (existing != null && existing.equalsIgnoreCase(image.getSha256())) {
            return true;
        }
        throw new IllegalStateException("同一任务图片序号已存在不同内容，拒绝覆盖");
    }

    public List<TaskInputImage> findByTaskId(Long taskId) {
        return jdbcTemplate.query(
                "SELECT * FROM task_input_images WHERE task_id = ? ORDER BY image_order ASC",
                mapper(), taskId);
    }

    private RowMapper<TaskInputImage> mapper() {
        return new RowMapper<TaskInputImage>() {
            @Override
            public TaskInputImage mapRow(ResultSet rs, int rowNum) throws java.sql.SQLException {
                TaskInputImage image = new TaskInputImage();
                image.setId(rs.getLong("id"));
                image.setTaskId(rs.getLong("task_id"));
                image.setImageOrder(rs.getInt("image_order"));
                image.setFileName(rs.getString("file_name"));
                image.setMediaType(rs.getString("media_type"));
                image.setByteSize(rs.getInt("byte_size"));
                image.setSha256(rs.getString("sha256"));
                image.setImageData(rs.getBytes("image_data"));
                image.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
                return image;
            }
        };
    }
}
