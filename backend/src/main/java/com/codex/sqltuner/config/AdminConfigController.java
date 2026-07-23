package com.codex.sqltuner.config;

import com.codex.sqltuner.auth.CurrentUser;
import com.codex.sqltuner.common.ApiResponse;
import com.codex.sqltuner.rule.RuleFinding;
import com.codex.sqltuner.rule.RuleSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpSession;
import javax.validation.Valid;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class AdminConfigController {
    private static final Logger log = LoggerFactory.getLogger(AdminConfigController.class);
    private final ModelConfigService modelConfigService;
    private final AdminRuntimeHealthService adminRuntimeHealthService;

    public AdminConfigController(ModelConfigService modelConfigService,
                                 AdminRuntimeHealthService adminRuntimeHealthService) {
        this.modelConfigService = modelConfigService;
        this.adminRuntimeHealthService = adminRuntimeHealthService;
    }

    @GetMapping("/health")
    public ApiResponse<RuntimeHealthView> health(HttpSession session) {
        CurrentUser.requireAdmin(session);
        log.info("health param 入参: admin: true");
        return ApiResponse.ok(adminRuntimeHealthService.view());
    }

    @GetMapping("/model-config")
    public ApiResponse<ModelConfigView> modelConfig(HttpSession session) {
        CurrentUser.requireAdmin(session);
        log.info("modelConfig param 入参: admin: true");
        return ApiResponse.ok(modelConfigService.view());
    }

    @GetMapping("/model-providers")
    public ApiResponse<List<ModelProviderOption>> modelProviders(HttpSession session) {
        CurrentUser.requireAdmin(session);
        log.info("modelProviders param 入参: admin: true");
        return ApiResponse.ok(modelConfigService.providers());
    }

    @PostMapping("/model-config")
    public ApiResponse<ModelConfigView> updateModelConfig(@RequestBody ModelConfigUpdateRequest request, HttpSession session) {
        CurrentUser.requireAdmin(session);
        log.info("updateModelConfig param 入参: provider: {}, model: {}, timeoutMs: {}",
                request.getProvider(), request.getModel(), request.getTimeoutMs());
        return ApiResponse.ok(modelConfigService.update(request));
    }

    @PostMapping("/model-config/test")
    public ApiResponse<ModelTestResult> testModelConfig(HttpSession session) {
        CurrentUser.requireAdmin(session);
        log.info("testModelConfig param 入参: admin: true");
        return ApiResponse.ok(modelConfigService.testConnection());
    }

    @PostMapping("/model-config/preview")
    public ApiResponse<ModelPreviewResult> previewModelConfig(@Valid @RequestBody ModelPreviewRequest request,
                                                               HttpSession session) {
        CurrentUser.requireAdmin(session);
        log.info("previewModelConfig param 入参: promptLength: {}, deepAnalysis: {}",
                request.getUserPrompt() == null ? 0 : request.getUserPrompt().length(), request.isDeepAnalysis());
        return ApiResponse.ok(modelConfigService.preview(request));
    }

    @PostMapping("/model-config/models")
    public ApiResponse<ModelCatalogView> discoverModels(@RequestBody(required = false) ModelCatalogRequest request, HttpSession session) {
        CurrentUser.requireAdmin(session);
        log.info("discoverModels param 入参: baseUrlConfigured: {}, apiKeyProvided: {}",
                request != null && request.getBaseUrl() != null && !request.getBaseUrl().trim().isEmpty(),
                request != null && request.getApiKey() != null && !request.getApiKey().trim().isEmpty());
        return ApiResponse.ok(modelConfigService.discoverModels(request));
    }

    @GetMapping("/rules")
    public ApiResponse<List<RuleFinding>> rules(HttpSession session) {
        CurrentUser.requireAdmin(session);
        log.info("rules param 入参: admin: true");
        return ApiResponse.ok(Arrays.asList(
                new RuleFinding("SELECT_STAR", RuleSeverity.WARN, "SELECT *", "扫描 SQL 文本", "只取需要字段"),
                new RuleFinding("LEADING_LIKE", RuleSeverity.HIGH, "LIKE 前置通配", "扫描 SQL 文本", "改写匹配方式或引入检索索引"),
                new RuleFinding("FUNCTION_ON_COLUMN", RuleSeverity.HIGH, "函数包列", "扫描 WHERE 函数", "将计算移到常量侧或使用生成列"),
                new RuleFinding("OR_CONDITION", RuleSeverity.WARN, "OR 条件", "扫描 SQL 文本", "检查索引或改写 UNION ALL"),
                new RuleFinding("NO_LIMIT", RuleSeverity.INFO, "MySQL 查询无 LIMIT", "OceanBase MySQL SELECT 未检测到 LIMIT", "列表查询增加分页或明确业务上限"),
                new RuleFinding("NO_PAGINATION", RuleSeverity.INFO, "Oracle 查询无分页", "OceanBase Oracle SELECT 未检测到 ROWNUM / FETCH FIRST", "列表查询增加 Oracle 兼容分页或明确业务上限"),
                new RuleFinding("EXPLAIN_FULL_SCAN", RuleSeverity.HIGH, "全表扫描提示", "扫描 EXPLAIN 文本", "结合过滤条件补充索引")
        ));
    }
}
