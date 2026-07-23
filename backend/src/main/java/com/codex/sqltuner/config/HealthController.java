package com.codex.sqltuner.config;

import com.codex.sqltuner.common.ApiResponse;
import com.codex.sqltuner.common.ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/health")
public class HealthController {
    private final ReadinessService readinessService;

    public HealthController(ReadinessService readinessService) {
        this.readinessService = readinessService;
    }

    @GetMapping("/live")
    public ApiResponse<Map<String, Object>> live() {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("status", "UP");
        return ApiResponse.ok(body);
    }

    @GetMapping("/ready")
    public ApiResponse<Map<String, Object>> ready() {
        ReadinessStatus readiness = readinessService.current();
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("status", readiness.getStatus());
        body.put("mysql", readiness.getMysql());
        body.put("scheduler", readiness.getScheduler());
        if (!readiness.isUp()) {
            throw new ApiException(503, "NOT_READY", "服务尚未就绪", body);
        }
        return ApiResponse.ok(body);
    }
}
