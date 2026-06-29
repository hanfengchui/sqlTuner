package com.codex.sqltuner.storage;

import com.codex.sqltuner.skill.SkillVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.function.Function;

/**
 * 单文件 JSON 状态存储。
 * 所有读写共享状态的操作都必须走 read/mutate，串行在同一把 lock 上，
 * 避免 repository 各自 synchronized 自身 bean 导致跨 repository 并发修改共享 LinkedHashMap。
 * state() 不再外泄可变活引用，调用方只能在临界区内通过回调访问 PersistentAppState。
 */
@Component
public class PersistentStateStore {
    private static final Logger log = LoggerFactory.getLogger(PersistentStateStore.class);
    private static final String DEFAULT_SKILL_NAME = "oceanbase-sql-tuning";
    private final StorageProperties storageProperties;
    private final com.codex.sqltuner.llm.LlmProperties llmProperties;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final CryptoSupport crypto;
    private final Object lock = new Object();
    private PersistentAppState state;
    private Path stateFile;

    public PersistentStateStore(StorageProperties storageProperties,
                                com.codex.sqltuner.llm.LlmProperties llmProperties,
                                com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                                CryptoSupport crypto) {
        this.storageProperties = storageProperties;
        this.llmProperties = llmProperties;
        this.objectMapper = objectMapper;
        this.crypto = crypto;
    }

    @PostConstruct
    public void init() {
        synchronized (lock) {
            this.stateFile = Paths.get(storageProperties.getDataDir(), "sql-tuner-state.json");
            this.state = loadOrCreate();
            llmProperties.apply(this.state.getModelConfig());
            log.info("persistentState init result 结果: path: {}", stateFile.toAbsolutePath());
            logMockState();
        }
    }

    /**
     * 只读临界区：在锁内读取快照交给回调，返回回调结果。
     * 回调拿到的 PersistentAppState 仍是共享对象，只读不改。
     */
    public <R> R read(Function<PersistentAppState, R> action) {
        synchronized (lock) {
            ensureLoaded();
            return action.apply(state);
        }
    }

    /**
     * 读写临界区：在锁内执行修改并在返回时自动落盘，保证 read-modify-write 原子。
     * 返回值为回调结果。一次调用对应一次持久化，不会漏写也不会中间态落盘。
     */
    public <R> R mutate(Function<PersistentAppState, R> action) {
        synchronized (lock) {
            ensureLoaded();
            R result = action.apply(state);
            persist();
            return result;
        }
    }

    private void ensureLoaded() {
        if (state == null) {
            state = loadOrCreate();
        }
    }

    private PersistentAppState loadOrCreate() {
        try {
            Files.createDirectories(stateFile.getParent());
            if (Files.exists(stateFile)) {
                PersistentAppState loaded = objectMapper.readValue(stateFile.toFile(), PersistentAppState.class);
                normalize(loaded);
                // 读入的 apiKey 是落盘密文，解密回内存明文供运行期使用。
                decryptApiKey(loaded);
                return loaded;
            }
            PersistentAppState created = new PersistentAppState();
            created.setModelConfig(defaultModelConfig());
            created.getSkills().put(DEFAULT_SKILL_NAME, defaultSkill(1L));
            persist(created);
            return created;
        } catch (IOException e) {
            throw new IllegalStateException("初始化持久化状态失败", e);
        }
    }

    private void decryptApiKey(PersistentAppState loaded) {
        ModelConfigRecord record = loaded.getModelConfig();
        if (record != null && record.getApiKey() != null) {
            String plain = crypto.decrypt(record.getApiKey());
            record.setApiKey(plain);
        }
    }

    private void normalize(PersistentAppState loaded) {
        if (loaded.getModelConfig() == null) {
            loaded.setModelConfig(defaultModelConfig());
        }
        if (loaded.getConversations() == null) {
            loaded.setConversations(new java.util.LinkedHashMap<Long, com.codex.sqltuner.conversation.Conversation>());
        }
        if (loaded.getMessages() == null) {
            loaded.setMessages(new java.util.LinkedHashMap<Long, java.util.List<com.codex.sqltuner.conversation.Message>>());
        }
        if (loaded.getTasks() == null) {
            loaded.setTasks(new java.util.LinkedHashMap<Long, com.codex.sqltuner.tuning.SqlTuningTask>());
        }
        if (loaded.getSkills() == null || loaded.getSkills().isEmpty()) {
            loaded.setSkills(new java.util.LinkedHashMap<String, SkillVersion>());
            loaded.getSkills().put(DEFAULT_SKILL_NAME, defaultSkill(1L));
            loaded.setSkillSequence(Math.max(loaded.getSkillSequence(), 1L));
        } else if (!loaded.getSkills().containsKey(DEFAULT_SKILL_NAME)) {
            long nextSkillId = loaded.getSkillSequence() + 1;
            loaded.setSkillSequence(nextSkillId);
            loaded.getSkills().put(DEFAULT_SKILL_NAME, defaultSkill(nextSkillId));
        }
    }

    private void persist() {
        persist(state);
    }

    private void persist(PersistentAppState snapshot) {
        // 落盘前把内存明文 apiKey 临时换成密文，序列化后再换回明文。
        // 全程在 lock 内，内存活对象对外始终是明文，落盘文件始终是密文。
        ModelConfigRecord record = snapshot.getModelConfig();
        String plainApiKey = record == null ? null : record.getApiKey();
        if (record != null && plainApiKey != null && !plainApiKey.isEmpty()) {
            record.setApiKey(crypto.encrypt(plainApiKey));
        }
        try {
            Files.createDirectories(stateFile.getParent());
            // 用唯一临时文件名，避免多实例共享数据目录时互踩（单实例仍不支持水平扩展）。
            Path temp = Files.createTempFile(stateFile.getParent(), "sql-tuner-state-", ".tmp");
            try {
                byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(snapshot);
                Files.write(temp, bytes);
                try {
                    Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Throwable t) {
                // 出错时清理临时文件，避免残留占位。
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 清理失败不影响主异常抛出。
                }
                throw t;
            }
        } catch (IOException e) {
            throw new IllegalStateException("保存持久化状态失败", e);
        } finally {
            // 无论成功失败，把内存里的 apiKey 还原成明文。
            if (record != null) {
                record.setApiKey(plainApiKey);
            }
        }
    }

    private ModelConfigRecord defaultModelConfig() {
        return new ModelConfigRecord(
                llmProperties.getProvider(),
                llmProperties.getBaseUrl(),
                llmProperties.getModel(),
                llmProperties.getTimeoutMs()
        );
    }

    private SkillVersion defaultSkill(Long id) {
        String content = "# OceanBase SQL 调优技能\n\n"
                + "你是面向 OceanBase MySQL 与 OceanBase Oracle 兼容模式的 SQL 调优专家。\n\n"
                + "## 必须遵守\n"
                + "- 不要编造表结构、索引、执行计划或数据量。\n"
                + "- 优先基于规则扫描结果、EXPLAIN、索引信息给建议。\n"
                + "- 不允许建议删除业务过滤条件。\n"
                + "- 索引建议必须说明收益、写入成本、验证方式。\n"
                + "- 必须遵守本轮 dbDialect，不要把 MySQL 专属语法建议到 Oracle 模式，也不要把 Oracle 专属分页建议到 MySQL 模式。\n"
                + "- 输出必须是 JSON 对象。\n\n"
                + "## 方言要点\n"
                + "- OceanBase MySQL: 可讨论 LIMIT、DATE_FORMAT、生成列、组合索引、函数索引，需说明版本和验证方式。\n"
                + "- OceanBase Oracle: 可讨论 ROWNUM、FETCH FIRST、TO_CHAR/TRUNC、函数索引、组合索引，避免使用 LIMIT、反引号和 DATE_FORMAT。\n\n"
                + "## JSON 字段\n"
                + "`summary`, `findings`, `rewriteSql`, `indexSuggestions`, `validationSteps`, `riskWarnings`, `needMoreInfo`。\n";
        return new SkillVersion(id, DEFAULT_SKILL_NAME, 1, content, true, java.time.LocalDateTime.now());
    }

    private void logMockState() {
        String provider = llmProperties.getProvider() == null ? "mock" : llmProperties.getProvider().trim();
        String apiKey = llmProperties.getApiKey();
        if ("mock".equalsIgnoreCase(provider)) {
            log.warn("persistentState init result 结果: llmMode: mock, provider: {}, hint: 当前未调用真实模型", provider);
            return;
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            log.warn("persistentState init result 结果: llmMode: fallback-mock, provider: {}, hint: 未检测到 API Key，真实模型调用将回退为 mock", provider);
            return;
        }
        log.info("persistentState init result 结果: llmMode: ready, provider: {}, model: {}", provider, llmProperties.getModel());
    }
}
