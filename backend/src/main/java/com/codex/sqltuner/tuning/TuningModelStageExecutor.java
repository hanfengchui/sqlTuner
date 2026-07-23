package com.codex.sqltuner.tuning;

import com.codex.sqltuner.llm.LlmCallException;
import com.codex.sqltuner.llm.LlmClient;
import com.codex.sqltuner.llm.LlmRequest;
import com.codex.sqltuner.llm.LlmRequestImage;
import com.codex.sqltuner.llm.LlmResponse;
import com.codex.sqltuner.tuning.inputimage.InputImageRepository;
import com.codex.sqltuner.tuning.inputimage.TaskInputImage;
import com.codex.sqltuner.tuning.inputimage.VisionExtractionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Executes durable model stages, including streamed analysis and image extraction.
 */
final class TuningModelStageExecutor {
    private static final Logger log = LoggerFactory.getLogger(TuningModelStageExecutor.class);
    private static final long STREAM_PROGRESS_MIN_INTERVAL_NANOS = 250L * 1000L * 1000L;
    private static final int STREAM_PROGRESS_CHAR_STEP = 256;
    private static final int STREAM_PROJECTOR_BUFFER_CHARS = 16 * 1024;
    private static final int STANDARD_TASK_BUDGET_MS = 150 * 1000;
    private static final int DEEP_TASK_BUDGET_MS = 300 * 1000;
    private static final String VISION_SYSTEM_PROMPT =
            "你是截图 OCR/视觉事实抽取器，只抽取图片中可见文字和执行计划事实。";
    private static final String VISION_EXTRACTION_PROMPT =
            "请从用户上传的 OceanBase SQL 执行计划截图或诊断截图中抽取可见事实。"
                    + "只返回严格 JSON，不要解释。字段必须包含 readable, operators, tables, rowEstimates, warnings, rawTextSummary。"
                    + "如果图片不可读，readable=false，并在 warnings 说明。不得编造图片中看不到的表、行数、算子。";

    private final TuningTaskRepository taskRepository;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final InputImageRepository inputImageRepository;
    private final TaskExecutionCoordinator taskExecution;
    private final ThreadLocal<TaskExecutionBudget> taskExecutionBudget = new ThreadLocal<TaskExecutionBudget>();

    TuningModelStageExecutor(TuningTaskRepository taskRepository,
                             LlmClient llmClient,
                             ObjectMapper objectMapper,
                             InputImageRepository inputImageRepository,
                             TaskExecutionCoordinator taskExecution) {
        this.taskRepository = taskRepository;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.inputImageRepository = inputImageRepository;
        this.taskExecution = taskExecution;
    }

    void startTaskBudget(boolean deepAnalysis) {
        taskExecutionBudget.set(new TaskExecutionBudget(deepAnalysis
                ? DEEP_TASK_BUDGET_MS
                : STANDARD_TASK_BUDGET_MS));
    }

    void clearTaskBudget() {
        taskExecutionBudget.remove();
    }

    LlmResponse analyzeStageWithStream(SqlTuningTask task, String stageName, LlmRequest request) {
        return analyzeStage(task, stageName, request, true);
    }

    LlmResponse analyzeStage(SqlTuningTask task, String stageName, LlmRequest request) {
        return analyzeStage(task, stageName, request, false);
    }

    /**
     * 已持久化的模型阶段可在租约重试后直接复用。未知网络结果仍可能重复计费，
     * 但不会因为进程在后续校验阶段失败而重复调用已经完成的阶段。
     */
    private LlmResponse analyzeStage(SqlTuningTask task,
                                     String stageName,
                                     LlmRequest request,
                                     boolean stream) {
        if (task == null || task.getId() == null) {
            throw new IllegalArgumentException("模型阶段必须绑定已持久化任务");
        }
        applyRemainingTaskBudget(request);
        String inputSha256 = stageInputSha256(stageName, request);
        java.util.Optional<TaskStageResult> existing = taskRepository.findCompletedStageResult(
                task.getId(), stageName, inputSha256);
        if (existing.isPresent()) {
            TaskStageResult saved = existing.get();
            log.info("analyzeStage result 结果: taskId: {}, stage: {}, reused: true, contentLength: {}",
                    task.getId(), stageName, saved.getContent() == null ? 0 : saved.getContent().length());
            return stageResponse(saved);
        }

        LlmResponse response = stream
                ? analyzeWithStream(task, request)
                : llmClient.analyze(request);
        TaskStageResult saved = taskRepository.saveCompletedStageResult(
                task.getId(),
                stageName,
                inputSha256,
                response.getProvider(),
                response.getModel(),
                response.getContent(),
                response.getElapsedMs(),
                response.isMock());
        return stageResponse(saved);
    }

    void maybeExtractPlanImages(SqlTuningTask task) {
        if (task.getInputImageCount() <= 0) {
            return;
        }
        if (inputImageRepository == null) {
            throw new IllegalStateException("图片输入仓储未初始化");
        }
        List<TaskInputImage> images = inputImageRepository.findByTaskId(task.getId());
        if (images.size() != task.getInputImageCount()) {
            throw new IllegalStateException("图片输入读取不完整");
        }
        List<LlmRequestImage> requestImages = new ArrayList<LlmRequestImage>();
        for (TaskInputImage image : images) {
            requestImages.add(new LlmRequestImage(image.toDataUrl()));
        }
        try {
            LlmResponse response = analyzeStage(task, "VISION_EXTRACT", new LlmRequest(
                    VISION_SYSTEM_PROMPT,
                    VISION_EXTRACTION_PROMPT,
                    false,
                    null,
                    requestImages));
            VisionExtractionResult vision = parseVisionExtractionAndRepairOnce(task, response, requestImages);
            task.setPlanImageFacts(summarizeVision(vision));
            taskExecution.addArtifact(task, "planImageVision",
                    vision.isReadable() ? "图片执行计划视觉抽取完成" : "图片不可读，保留提示但不计入证据目录",
                    vision);
        } catch (LlmCallException error) {
            VisionExtractionResult unavailable = new VisionExtractionResult();
            unavailable.setReadable(false);
            unavailable.getWarnings().add("视觉模型调用失败，图片未计入证据目录");
            unavailable.setRawTextSummary("视觉模型调用失败，请补充清晰截图或文本 EXPLAIN");
            task.setPlanImageFacts(summarizeVision(unavailable));
            taskExecution.addArtifact(task, "planImageVisionUnavailable",
                    "视觉模型暂不可用，已跳过图片并继续文本 SQL 分析",
                    error.getClass().getSimpleName());
        }
    }

    private LlmResponse stageResponse(TaskStageResult saved) {
        return new LlmResponse(
                saved.getProvider(),
                saved.getModel(),
                saved.getContent(),
                saved.getElapsedMs(),
                saved.isMock());
    }

    private void applyRemainingTaskBudget(LlmRequest request) {
        TaskExecutionBudget budget = taskExecutionBudget.get();
        if (budget == null) {
            return;
        }
        int remainingMs = budget.remainingMs();
        if (remainingMs <= 0) {
            throw new LlmCallException("调优任务超过总时限", null, false);
        }
        Integer requested = request.getCallTimeoutMs();
        if (requested == null || requested <= 0 || requested > remainingMs) {
            request.setCallTimeoutMs(remainingMs);
        }
    }

    private String stageInputSha256(String stageName, LlmRequest request) {
        StringBuilder material = new StringBuilder();
        material.append(stageName == null ? "" : stageName).append('\n')
                .append(request.getSystemPrompt() == null ? "" : request.getSystemPrompt()).append('\n')
                .append(request.getUserPrompt() == null ? "" : request.getUserPrompt()).append('\n')
                .append(request.getModelOverride() == null ? "" : request.getModelOverride()).append('\n')
                .append(request.isDeepAnalysis()).append('\n')
                .append(request.isJsonOutput());
        if (request.getImages() != null) {
            for (LlmRequestImage image : request.getImages()) {
                material.append('\n').append(image == null || image.getDataUrl() == null ? "" : image.getDataUrl());
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("模型阶段输入摘要失败", e);
        }
    }

    private LlmResponse analyzeWithStream(final SqlTuningTask task, LlmRequest request) {
        taskExecution.resetModelStream(task);
        final ModelStreamProjector projector = new ModelStreamProjector(objectMapper);
        final StringBuilder accumulatedModelContent = new StringBuilder();
        final String[] lastCallbackContent = new String[]{""};
        final String[] lastDraft = new String[]{""};
        final int[] lastPublishedChars = new int[]{-1};
        final long[] lastPublishedAt = new long[]{0L};
        return llmClient.analyze(request, new com.codex.sqltuner.llm.LlmStreamListener() {
            @Override
            public void onContent(String deltaContent, int receivedChars) {
                if (TuningText.hasText(deltaContent)) {
                    if (deltaContent.startsWith(lastCallbackContent[0])) {
                        accumulatedModelContent.setLength(0);
                        accumulatedModelContent.append(deltaContent);
                    } else {
                        accumulatedModelContent.append(deltaContent);
                    }
                    lastCallbackContent[0] = deltaContent;
                    if (accumulatedModelContent.length() > STREAM_PROJECTOR_BUFFER_CHARS) {
                        accumulatedModelContent.delete(0, accumulatedModelContent.length() - STREAM_PROJECTOR_BUFFER_CHARS);
                    }
                }
                String draft = projector.project(accumulatedModelContent);
                long now = System.nanoTime();
                // 后续字段一旦触发保守 SQL 门禁，projector 会返回空串。此时保留已经发布的
                // 最后一版安全叙事，不能让界面退回空白，也不能继续广播被门禁的内容。
                if (!TuningText.hasText(draft) && TuningText.hasText(lastDraft[0])) {
                    return;
                }
                if (draft.equals(lastDraft[0])) {
                    if (TuningText.hasText(draft)) {
                        return;
                    }
                    boolean intervalElapsed = lastPublishedAt[0] == 0L
                            || now - lastPublishedAt[0] >= STREAM_PROGRESS_MIN_INTERVAL_NANOS;
                    boolean enoughProgress = lastPublishedChars[0] < 0
                            || receivedChars - lastPublishedChars[0] >= STREAM_PROGRESS_CHAR_STEP;
                    if (!intervalElapsed && !enoughProgress) {
                        return;
                    }
                }
                lastDraft[0] = draft;
                lastPublishedChars[0] = receivedChars;
                lastPublishedAt[0] = now;
                String phase = TuningText.hasText(draft) ? (request.isDeepAnalysis() ? "VERIFYING" : "ANSWER") : "THINKING";
                taskExecution.publishModelStream(task, new TaskStreamChunk(phase, draft, receivedChars, 0L));
            }
        });
    }

    private VisionExtractionResult parseVisionExtractionAndRepairOnce(SqlTuningTask task,
                                                                       LlmResponse response,
                                                                       List<LlmRequestImage> requestImages) {
        try {
            return parseVisionExtraction(response);
        } catch (IllegalStateException firstFailure) {
            taskExecution.addArtifact(task, "planImageVisionValidateFailed", "图片视觉输出未通过严格校验，执行一次修复",
                    firstFailure.getMessage());
            String repairPrompt = "上一次图片事实抽取输出未通过严格校验。请重新查看随请求附带的原始图片，"
                    + "只返回完整严格 JSON，不要解释。字段必须包含 readable, operators, tables, rowEstimates, warnings, rawTextSummary。"
                    + "不可读时返回 readable=false，不得编造。校验错误: " + firstFailure.getMessage()
                    + "。上一次输出: " + TuningText.abbreviate(response.getContent(), 4000);
            LlmResponse repaired = analyzeStage(task, "VISION_REPAIR", new LlmRequest(
                    VISION_SYSTEM_PROMPT,
                    repairPrompt,
                    false,
                    null,
                    requestImages));
            taskExecution.addArtifact(task, "planImageVisionRepair", "图片视觉 JSON 修复调用完成", repaired);
            try {
                return parseVisionExtraction(repaired);
            } catch (IllegalStateException secondFailure) {
                VisionExtractionResult unreadable = new VisionExtractionResult();
                unreadable.setReadable(false);
                unreadable.getWarnings().add("视觉模型两次未返回可解析 JSON，图片未计入证据目录");
                unreadable.setRawTextSummary("未能从图片中提取可核验事实，请补充清晰截图或文本 EXPLAIN");
                taskExecution.addArtifact(task, "planImageVisionRepairFailed", "图片视觉修复仍不可解析，按不可读证据降级", secondFailure.getMessage());
                return unreadable;
            }
        }
    }

    private VisionExtractionResult parseVisionExtraction(LlmResponse response) {
        String json = stripSingleJsonFence(response.getContent());
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isObject()) {
                throw new IllegalStateException("视觉模型输出根节点不是对象");
            }
            VisionExtractionResult vision = new VisionExtractionResult();
            JsonNode operators = firstNode(root, "operators", "operatorList", "executionPlanOperators");
            JsonNode tables = firstNode(root, "tables", "tableNames", "tableList");
            JsonNode rows = firstNode(root, "rowEstimates", "row_estimates", "estimatedRows", "rows");
            vision.setOperators(objectValues(operators));
            vision.setTables(objectValues(tables));
            vision.setRowEstimates(objectValues(rows));
            vision.setWarnings(stringValues(firstNode(root, "warnings", "warning", "notes")));
            String summary = firstText(root, "rawTextSummary", "raw_text_summary", "summary", "visibleText", "ocrText");
            if (!TuningText.hasText(summary)) {
                summary = recognizedVisionSummary(vision);
            }
            boolean hasFacts = !vision.getOperators().isEmpty() || !vision.getTables().isEmpty()
                    || !vision.getRowEstimates().isEmpty() || TuningText.hasText(summary);
            JsonNode readable = root.get("readable");
            vision.setReadable(readable != null && readable.isBoolean() ? readable.asBoolean() : hasFacts);
            if (readable == null && hasFacts) {
                vision.getWarnings().add("模型未显式返回 readable，后端依据已抽取事实按可读处理");
            }
            if (!TuningText.hasText(summary)) {
                vision.setReadable(false);
                summary = "未能从图片中提取可核验事实";
            }
            vision.setRawTextSummary(summary);
            return vision;
        } catch (Exception e) {
            log.warn("parseVisionExtraction result 结果: 视觉输出不是严格 JSON, errorType: {}",
                    e.getClass().getSimpleName());
            throw new IllegalStateException("视觉模型输出不是合法 JSON 或缺少必要字段", e);
        }
    }

    private JsonNode firstNode(JsonNode root, String... names) {
        for (String name : names) {
            JsonNode value = root.get(name);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private List<Object> objectValues(JsonNode node) {
        List<Object> values = new ArrayList<Object>();
        if (node == null || node.isNull()) {
            return values;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                values.add(objectMapper.convertValue(item, Object.class));
            }
        } else {
            values.add(objectMapper.convertValue(node, Object.class));
        }
        return values;
    }

    private List<String> stringValues(JsonNode node) {
        List<String> values = new ArrayList<String>();
        if (node == null || node.isNull()) {
            return values;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                values.add(item.isTextual() ? item.asText() : TuningText.abbreviate(item.toString(), 500));
            }
        } else {
            values.add(node.isTextual() ? node.asText() : TuningText.abbreviate(node.toString(), 500));
        }
        return values;
    }

    private String recognizedVisionSummary(VisionExtractionResult vision) {
        if (vision.getOperators().isEmpty() && vision.getTables().isEmpty() && vision.getRowEstimates().isEmpty()) {
            return "";
        }
        return "operators=" + vision.getOperators()
                + "; tables=" + vision.getTables()
                + "; rowEstimates=" + vision.getRowEstimates();
    }

    private String summarizeVision(VisionExtractionResult vision) {
        StringBuilder builder = new StringBuilder();
        builder.append("readable=").append(vision.isReadable()).append("\n");
        builder.append("operators=").append(vision.getOperators()).append("\n");
        builder.append("tables=").append(vision.getTables()).append("\n");
        builder.append("rowEstimates=").append(vision.getRowEstimates()).append("\n");
        builder.append("warnings=").append(vision.getWarnings()).append("\n");
        builder.append("rawTextSummary=").append(TuningText.abbreviate(vision.getRawTextSummary(), 2000));
        return builder.toString();
    }

    private String firstText(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (!value.isMissingNode() && !value.isNull()) {
                String text = value.asText("");
                if (TuningText.hasText(text)) {
                    return text;
                }
            }
        }
        return "";
    }

    private String stripSingleJsonFence(String content) {
        if (content == null) {
            return "";
        }
        String trimmed = content.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstLineEnd = trimmed.indexOf('\n');
        if (firstLineEnd < 0 || !trimmed.endsWith("```")) {
            return trimmed;
        }
        String opener = trimmed.substring(0, firstLineEnd).trim();
        if (!"```".equals(opener) && !"```json".equalsIgnoreCase(opener)) {
            return trimmed;
        }
        return trimmed.substring(firstLineEnd + 1, trimmed.length() - 3).trim();
    }
}
