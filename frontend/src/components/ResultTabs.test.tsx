import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { SqlTuningTask } from "../types/api";
import { ResultTabs } from "./ResultTabs";

const baseTask: SqlTuningTask = {
  id: 7,
  userId: 1,
  conversationId: 3,
  dbDialect: "OceanBase MySQL",
  originalSql: "select * from orders where id = 1",
  deepAnalysis: false,
  status: "DONE",
  statusMessage: "完成",
  ruleFindings: [],
  artifacts: [],
  createdAt: "2026-07-17T09:00:00Z",
  updatedAt: "2026-07-17T09:00:00Z"
};

describe("ResultTabs", () => {
  it("renders backend structured evidence, diagnosis and validation DTOs", async () => {
    render(
      <ResultTabs
        task={{
          ...baseTask,
          result: {
            outcome: "NEEDS_INPUT",
            summary: "缺少执行计划，不能生成确定性 DDL。",
            contextAssessment: {
              completeness: "SQL_SCHEMA",
              maxConfidence: "LOW",
              policyNotes: ["证据不足时不生成确定性 DDL"]
            },
            evidenceCatalog: [{ id: "E1", source: "schema", summary: "orders(id, status)", trustLevel: "HIGH" }],
            diagnoses: [{
              title: "可能全表扫描",
              severity: "WARN",
              confidence: "LOW",
              impact: "缺少 explain 只能低置信判断",
              precondition: "需要补充 EXPLAIN 后确认",
              evidenceRefs: ["E1"]
            }],
            rewriteCandidates: [],
            indexCandidates: [],
            validationPlan: [{ action: "补充 EXPLAIN", expectedSignal: "确认访问路径和扫描行数", evidenceRefs: ["E1"] }],
            missingInformation: ["当前索引"],
            safetyWarnings: ["信息不足"],
            findings: [],
            rewriteSql: "",
            indexSuggestions: [],
            validationSteps: [],
            riskWarnings: [],
            needMoreInfo: [],
            rawModelOutput: "",
            mockModel: false
          }
        }}
      />
    );

    expect(screen.getByText("需要更多证据")).toBeInTheDocument();
    expect(screen.getByText("orders(id, status)")).toBeInTheDocument();
    expect(screen.getByText("可信等级：HIGH")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("tab", { name: "诊断" }));
    expect(screen.getByText("可能全表扫描")).toBeInTheDocument();
    expect(screen.getByText("前置条件：需要补充 EXPLAIN 后确认")).toBeInTheDocument();
    expect(screen.getByText("E1")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("tab", { name: "验证" }));
    expect(screen.getByText("补充 EXPLAIN；观察: 确认访问路径和扫描行数；证据: E1")).toBeInTheDocument();
  });

  it("renders backend rewrite and index candidate field names", async () => {
    render(
      <ResultTabs
        task={{
          ...baseTask,
          result: {
            outcome: "ADVICE",
            summary: "可审阅建议",
            contextAssessment: { completeness: "FULL_EVIDENCE", maxConfidence: "HIGH" },
            evidenceCatalog: [{ id: "E_EXPLAIN", source: "USER_EXPLAIN", summary: "TABLE ACCESS BY INDEX", trustLevel: "MEDIUM" }],
            diagnoses: [],
            rewriteCandidates: [{
              sql: "select id from orders where status = ? order by created_at desc",
              change: "只投影必要列并保留排序",
              semanticCheck: "语句类型、表集合和 WHERE 条件保持一致",
              risk: "需确认调用方不依赖 select *",
              validation: "对比结果集和执行计划",
              evidenceRefs: ["E_EXPLAIN"]
            }],
            indexCandidates: [{
              tableName: "orders",
              columnOrder: ["status", "created_at"],
              ddl: "create index idx_orders_status_created on orders(status, created_at)",
              benefit: "减少排序和扫描",
              writeCost: "增加写入维护成本",
              risk: "写多场景需评估",
              validation: "EXPLAIN 确认命中 idx_orders_status_created",
              confidence: "HIGH",
              evidenceRefs: ["E_EXPLAIN"]
            }],
            validationPlan: [{ action: "执行 EXPLAIN", expectedSignal: "使用新索引", evidenceRefs: ["E_EXPLAIN"] }],
            missingInformation: [],
            safetyWarnings: [],
            findings: [],
            rewriteSql: "",
            indexSuggestions: [],
            validationSteps: [],
            riskWarnings: [],
            needMoreInfo: [],
            rawModelOutput: "",
            mockModel: false
          }
        }}
      />
    );

    await userEvent.click(screen.getByRole("tab", { name: "改写" }));
    expect(screen.getByText(/order by created_at desc/)).toBeInTheDocument();
    expect(screen.getByText("只投影必要列并保留排序")).toBeInTheDocument();
    expect(screen.getByText("语句类型、表集合和 WHERE 条件保持一致")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("tab", { name: "索引" }));
    expect(screen.getByText("orders · HIGH")).toBeInTheDocument();
    expect(screen.getByText("create index idx_orders_status_created on orders(status, created_at)")).toBeInTheDocument();
    expect(screen.getByText("EXPLAIN 确认命中 idx_orders_status_created")).toBeInTheDocument();
  });

  it("falls back to legacy fields while backend migrates", async () => {
    render(
      <ResultTabs
        task={{
          ...baseTask,
          result: {
            summary: "旧结构结果",
            findings: [{ title: "LIKE 前置通配", evidence: "name like '%a'", impact: "索引难以利用", confidence: "MEDIUM" }],
            rewriteSql: "select id from users where name like concat(:prefix, '%')",
            indexSuggestions: [{ indexName: "idx_users_name", columns: ["name"], benefit: "减少扫描", risk: "写入成本", validation: "EXPLAIN" }],
            validationSteps: ["对比执行计划"],
            riskWarnings: ["确认语义"],
            needMoreInfo: [],
            rawModelOutput: "",
            mockModel: true
          }
        }}
      />
    );

    await userEvent.click(screen.getByRole("tab", { name: "改写" }));
    expect(screen.getByText(/concat/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole("tab", { name: "索引" }));
    expect(screen.getByText("idx_users_name")).toBeInTheDocument();
  });
});
