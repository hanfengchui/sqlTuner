package com.codex.sqltuner.storage;

import com.codex.sqltuner.conversation.Conversation;
import com.codex.sqltuner.conversation.ConversationRepository;
import com.codex.sqltuner.llm.LlmProperties;
import com.codex.sqltuner.tuning.SqlTuningTask;
import com.codex.sqltuner.tuning.TaskStatus;
import com.codex.sqltuner.tuning.TuningTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证统一存储锁：多线程并发跨 repository 读写共享状态，
 * 不丢更新、不撕坏 LinkedHashMap、序列号无重复无空洞、最终落盘一致。
 */
class PersistentStateStoreConcurrencyTest {
    @TempDir
    Path tempDir;

    @Test
    void concurrentCreatesAcrossRepositoriesKeepConsistency() throws Exception {
        ObjectMapper objectMapper = objectMapper();
        PersistentStateStore store = store(objectMapper);
        ConversationRepository conversations = new ConversationRepository(store);
        TuningTaskRepository tasks = new TuningTaskRepository(store);

        int threads = 16;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<Future<?>>();
            for (int i = 0; i < threads; i++) {
                final long userId = 100L + i;
                futures.add(pool.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            start.await();
                            for (int j = 0; j < perThread; j++) {
                                Conversation c = conversations.create(userId, "会话-" + UUID.randomUUID());
                                // 同一线程内会话-任务交替创建，跨 repository 混合写。
                                SqlTuningTask task = new SqlTuningTask();
                                task.setUserId(userId);
                                task.setConversationId(c.getId());
                                task.setDbDialect("OceanBase/MySQL");
                                task.setInputType("sql");
                                task.setOriginalSql("select 1");
                                task.setStatus(TaskStatus.RECEIVED);
                                task.setStatusMessage("ok");
                                tasks.create(task);
                                tasks.update(task);
                            }
                        } catch (Throwable t) {
                            throw new RuntimeException(t);
                        }
                    }
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 全部完成后：会话数 = threads*perThread，任务数同。
        int expected = threads * perThread;
        assertThat(conversations.listByUser(100L).size()).isEqualTo(perThread);
        // 重新加载一次，验证落盘内容可解析且数量正确（不撕裂）。
        PersistentStateStore restarted = store(objectMapper);
        PersistentAppState reloaded = restarted.read(new java.util.function.Function<PersistentAppState, PersistentAppState>() {
            @Override
            public PersistentAppState apply(PersistentAppState s) {
                return s;
            }
        });
        assertThat(reloaded.getConversations().size()).isEqualTo(expected);
        assertThat(reloaded.getTasks().size()).isEqualTo(expected);
        // 序列号无重复（LinkedHashMap 值唯一即可），数量正确即隐含无重复 id。
        assertThat(reloaded.getConversationSequence()).isEqualTo(100L + expected);
        assertThat(reloaded.getTaskSequence()).isEqualTo(1000L + expected);
        // JSON 文件本身可被原生字符串校验：不含撕裂的中间态（能被 ObjectMapper 解析已证明）。
        assertThat(Files.exists(tempDir.resolve("sql-tuner-state.json"))).isTrue();
    }

    @Test
    void concurrentReadsDoNotThrowConcurrentModification() throws Exception {
        ObjectMapper objectMapper = objectMapper();
        PersistentStateStore store = store(objectMapper);
        ConversationRepository conversations = new ConversationRepository(store);
        for (int i = 0; i < 50; i++) {
            conversations.create(1L, "seed-" + i);
        }

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger errors = new AtomicInteger();
            List<Future<?>> futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            start.await();
                            for (int j = 0; j < 100; j++) {
                                conversations.listByUser(1L);
                            }
                        } catch (Throwable t) {
                            errors.incrementAndGet();
                            throw new RuntimeException(t);
                        }
                    }
                }));
                futures.add(pool.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            start.await();
                            for (int j = 0; j < 100; j++) {
                                conversations.create(1L, "c-" + j);
                            }
                        } catch (Throwable t) {
                            errors.incrementAndGet();
                            throw new RuntimeException(t);
                        }
                    }
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
            assertThat(errors.get()).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    private PersistentStateStore store(ObjectMapper objectMapper) {
        StorageProperties storageProperties = new StorageProperties();
        storageProperties.setDataDir(tempDir.toString());
        PersistentStateStore store = new PersistentStateStore(storageProperties, new LlmProperties(), objectMapper, new CryptoSupport());
        store.init();
        return store;
    }

    private ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return objectMapper;
    }
}
