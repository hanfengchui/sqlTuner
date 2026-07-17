package com.codex.sqltuner.config;

import com.codex.sqltuner.common.ApiResponse;
import com.codex.sqltuner.common.ApiException;
import com.codex.sqltuner.tuning.TuningTaskRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/health")
public class HealthController {
    private final JdbcTemplate jdbcTemplate;
    private final TuningTaskRepository taskRepository;

    public HealthController(JdbcTemplate jdbcTemplate, TuningTaskRepository taskRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.taskRepository = taskRepository;
    }

    @GetMapping("/live")
    public ApiResponse<Map<String, Object>> live() {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("status", "UP");
        return ApiResponse.ok(body);
    }

    @GetMapping("/ready")
    public ApiResponse<Map<String, Object>> ready() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            Map<String, Object> body = new HashMap<String, Object>();
            body.put("status", "UP");
            body.put("mysql", "UP");
            body.put("scheduler", "UP");
            body.put("queued", taskRepository.queuedCount());
            body.put("running", taskRepository.runningCount());
            return ApiResponse.ok(body);
        } catch (Exception e) {
            throw new ApiException(503, "NOT_READY", "服务尚未就绪");
        }
    }
}
