package com.codex.sqltuner.skill;

import com.codex.sqltuner.common.NotFoundException;
import com.codex.sqltuner.storage.PersistentAppState;
import com.codex.sqltuner.storage.PersistentStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

@Repository
public class SkillRepository {
    private static final Logger log = LoggerFactory.getLogger(SkillRepository.class);
    static final String DEFAULT_SKILL_NAME = "oceanbase-sql-tuning";
    private final PersistentStateStore stateStore;

    public SkillRepository(PersistentStateStore stateStore) {
        this.stateStore = stateStore;
    }

    public List<SkillVersion> list() {
        return stateStore.read(new Function<PersistentAppState, List<SkillVersion>>() {
            @Override
            public List<SkillVersion> apply(PersistentAppState state) {
                // 返回副本列表，避免外泄共享 map 被并发修改。
                return new ArrayList<SkillVersion>(state.getSkills().values());
            }
        });
    }

    public SkillVersion activeDefault() {
        SkillVersion skill = stateStore.read(new Function<PersistentAppState, SkillVersion>() {
            @Override
            public SkillVersion apply(PersistentAppState state) {
                return state.getSkills().get(DEFAULT_SKILL_NAME);
            }
        });
        if (skill == null || !skill.isEnabled()) {
            throw new NotFoundException("默认 SQL 调优技能不存在");
        }
        log.info("activeDefaultSkill result 结果: skillName: {}, version: {}", skill.getName(), skill.getVersion());
        return skill;
    }

    public SkillVersion save(final String name, final String content) {
        SkillVersion saved = stateStore.mutate(new Function<PersistentAppState, SkillVersion>() {
            @Override
            public SkillVersion apply(PersistentAppState state) {
                SkillVersion old = state.getSkills().get(name);
                Integer nextVersion = old == null ? 1 : old.getVersion() + 1;
                long id = state.getSkillSequence() + 1;
                state.setSkillSequence(id);
                SkillVersion skill = new SkillVersion(id, name, nextVersion, content, true, LocalDateTime.now());
                state.getSkills().put(name, skill);
                return skill;
            }
        });
        log.info("saveSkill result 结果: skillName: {}, version: {}", name, saved.getVersion());
        return saved;
    }

    public void setEnabled(final String name, final boolean enabled) {
        stateStore.mutate(new Function<PersistentAppState, Void>() {
            @Override
            public Void apply(PersistentAppState state) {
                SkillVersion skill = state.getSkills().get(name);
                if (skill == null) {
                    throw new NotFoundException("技能不存在");
                }
                skill.setEnabled(enabled);
                return null;
            }
        });
    }

    private SkillVersion defaultSkill() {
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
        return new SkillVersion(1L, DEFAULT_SKILL_NAME, 1, content, true, LocalDateTime.now());
    }
}
