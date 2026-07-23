package com.codex.sqltuner.config;

import com.codex.sqltuner.tuning.TuningTaskRepository;
import org.springframework.stereotype.Service;

@Service
public class AdminRuntimeHealthService {
    private final ModelConfigService modelConfigService;
    private final TuningTaskRepository taskRepository;
    private final RetentionProperties retentionProperties;
    private final ReadinessService readinessService;

    public AdminRuntimeHealthService(ModelConfigService modelConfigService,
                                     TuningTaskRepository taskRepository,
                                     RetentionProperties retentionProperties,
                                     ReadinessService readinessService) {
        this.modelConfigService = modelConfigService;
        this.taskRepository = taskRepository;
        this.retentionProperties = retentionProperties;
        this.readinessService = readinessService;
    }

    public RuntimeHealthView view() {
        RuntimeHealthView health = modelConfigService.healthView();
        ReadinessStatus readiness = readinessService.current();
        health.setMysql(readiness.getMysql());
        health.setScheduler(readiness.getScheduler());
        health.setQueued(taskRepository.queuedCount());
        health.setRunning(taskRepository.runningCount());
        health.setRetentionEnabled(retentionProperties.isEnabled());
        health.setRetentionDays(retentionProperties.getConversationDays());
        return health;
    }
}
