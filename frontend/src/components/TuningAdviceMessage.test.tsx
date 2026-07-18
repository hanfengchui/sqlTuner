import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { SqlTuningTask } from "../types/api";
import { TaskProgressMessage, TuningAdviceMessage } from "./TuningAdviceMessage";

const baseTask: SqlTuningTask = {
  id: 7,
  userId: 1,
  conversationId: 3,
  dbDialect: "OceanBase MySQL",
  originalSql: "select * from orders where id = 1",
  deepAnalysis: false,
  status: "DONE",
  statusMessage: "调优建议已生成",
  ruleFindings: [],
  artifacts: [],
  createdAt: "2026-07-18T09:00:00Z",
  updatedAt: "2026-07-18T09:00:00Z"
};

describe("TuningAdviceMessage", () => {
  it("renders a concise validated answer without report tabs or evidence identifiers", () => {
    render(
      <TuningAdviceMessage
        progressive={false}
        task={{
          ...baseTask,
          result: {
            outcome: "ADVICE",
            summary: "当前查询应优先缩小投影列，并确认排序是否命中已有索引。",
            evidenceCatalog: [{ id: "E_EXPLAIN", source: "USER_EXPLAIN", summary: "TABLE ACCESS", trustLevel: "HIGH" }],
            diagnoses: [
              { title: "SELECT * 扩大回表成本", impact: "返回列过多会增加 I/O", confidence: "MEDIUM" },
              { title: "排序可能触发额外排序", impact: "需要确认访问路径", confidence: "LOW" },
              { title: "第三个问题", impact: "保留", confidence: "LOW" },
              { title: "不应默认展示", impact: "超过上限", confidence: "LOW" }
            ],
            rewriteCandidates: [{
              sql: "select id, status from orders where id = ?",
              change: "仅保留调用方实际使用的列",
              risk: "确认调用方不依赖其他列"
            }],
            indexCandidates: [{
              tableName: "orders",
              columnOrder: ["status", "created_at"],
              ddl: "create index idx_orders_status_created on orders(status, created_at)",
              benefit: "减少排序和扫描",
              writeCost: "增加写维护"
            }],
            validationPlan: [{ action: "执行 EXPLAIN", expectedSignal: "确认访问路径" }],
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

    expect(screen.getByText("已校验")).toBeInTheDocument();
    expect(screen.getByText("重点问题")).toBeInTheDocument();
    expect(screen.getByText("SELECT * 扩大回表成本")).toBeInTheDocument();
    expect(screen.getByText("第三个问题")).toBeInTheDocument();
    expect(screen.queryByText("不应默认展示")).not.toBeInTheDocument();
    expect(screen.getByText("建议改写")).toBeInTheDocument();
    expect(screen.getByText(/create index idx_orders_status_created/)).toBeInTheDocument();
    expect(screen.getByText("验证")).toBeInTheDocument();
    expect(screen.queryByText("E_EXPLAIN")).not.toBeInTheDocument();
    expect(screen.queryByRole("tab")).not.toBeInTheDocument();
  });

  it("shows only the next required input when the evidence gate needs more data", () => {
    render(
      <TuningAdviceMessage
        progressive={false}
        task={{
          ...baseTask,
          result: {
            outcome: "NEEDS_INPUT",
            summary: "当前信息不足，不能生成确定性索引 DDL。",
            diagnoses: [{ title: "可能全表扫描", impact: "缺少计划只能低置信判断" }],
            rewriteCandidates: [],
            indexCandidates: [],
            validationPlan: [],
            missingInformation: ["文本 EXPLAIN", "当前索引定义"],
            safetyWarnings: ["不要直接上线索引 DDL"],
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

    expect(screen.getByText("需要补充")).toBeInTheDocument();
    expect(screen.getByText("请补充：文本 EXPLAIN")).toBeInTheDocument();
    expect(screen.queryByText("当前索引定义")).not.toBeInTheDocument();
    expect(screen.getByText("注意：不要直接上线索引 DDL")).toBeInTheDocument();
  });

  it("uses the readable narrative as the primary answer without repeating legacy diagnoses", () => {
    render(
      <TuningAdviceMessage
        progressive={false}
        task={{
          ...baseTask,
          result: {
            outcome: "ADVICE",
            summary: "旧摘要不应成为主答案。",
            analysisNarrative: {
              conclusion: "当前没有足够证据确认索引方案；先补齐执行计划，再判断扫描和排序是否真的存在。",
              sections: [
                {
                  kind: "EVIDENCE",
                  title: "为何这样判断",
                  body: "已知 SQL 包含筛选和排序，但没有可验证的文本执行计划或现有索引定义。",
                  evidenceRefs: ["E_SQL"]
                },
                {
                  kind: "CAUTION",
                  title: "暂不执行的操作",
                  body: "历史报告中的全表扫描和索引建议仍是待核验主张，不能直接用于生产变更。",
                  evidenceRefs: ["E_SQL"]
                },
                {
                  kind: "VALIDATION",
                  title: "下一步验证",
                  body: "补充文本 EXPLAIN、表结构和当前索引后，比较访问路径与排序算子。",
                  evidenceRefs: ["E_SQL"]
                }
              ]
            },
            evidenceCatalog: [{ id: "E_SQL", source: "SQL", summary: "用户提供的 SQL", trustLevel: "HIGH" }],
            diagnoses: [{ title: "旧诊断不应重复", impact: "已经由叙事覆盖", confidence: "LOW" }],
            rewriteCandidates: [],
            indexCandidates: [],
            validationPlan: [{ action: "旧验证计划", expectedSignal: "不应重复" }],
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

    expect(screen.getByText("当前没有足够证据确认索引方案；先补齐执行计划，再判断扫描和排序是否真的存在。")).toBeInTheDocument();
    expect(screen.getByText("为何这样判断")).toBeInTheDocument();
    expect(screen.getByText("暂不执行的操作")).toBeInTheDocument();
    expect(screen.getByText("下一步验证")).toBeInTheDocument();
    expect(screen.queryByText("重点问题")).not.toBeInTheDocument();
    expect(screen.queryByText("旧诊断不应重复")).not.toBeInTheDocument();
    expect(screen.queryByText("E_SQL")).not.toBeInTheDocument();
  });

  it("streams only task stages while validation is still in progress", () => {
    render(<TaskProgressMessage task={{ ...baseTask, status: "VERIFYING", statusMessage: "正在校验模型结构化输出" }} />);

    expect(screen.getByText("正在校验模型结构化输出")).toBeInTheDocument();
    expect(screen.queryByText("调优建议已生成")).not.toBeInTheDocument();
  });
});
