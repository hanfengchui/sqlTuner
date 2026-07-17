import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { SqlTuningTask } from "../types/api";
import { TuningEvidenceDetails } from "./TuningEvidenceDetails";

const task: SqlTuningTask = {
  id: 12,
  userId: 1,
  conversationId: 1,
  dbDialect: "OceanBase MySQL",
  originalSql: "select * from orders where status = ?",
  deepAnalysis: true,
  status: "DONE",
  statusMessage: "调优建议已生成",
  ruleFindings: [{ code: "R_ORDER", severity: "WARN", title: "排序检查", evidence: "order by", suggestion: "确认排序索引" }],
  artifacts: [{ nodeName: "ruleCheck", summary: "规则扫描完成", payload: {}, createdAt: "2026-07-18T09:00:00Z" }],
  createdAt: "2026-07-18T09:00:00Z",
  updatedAt: "2026-07-18T09:01:00Z",
  result: {
    outcome: "ADVICE",
    summary: "可审阅建议",
    contextAssessment: {
      completeness: "FULL_EVIDENCE",
      maxConfidence: "HIGH",
      availableEvidence: ["SQL", "文本 EXPLAIN"],
      missingInformation: ["调用频次"],
      policyNotes: ["DDL 已通过门禁"]
    },
    evidenceCatalog: [{ id: "E_EXPLAIN", source: "USER_EXPLAIN", summary: "TABLE ACCESS BY INDEX", trustLevel: "HIGH" }],
    diagnoses: [{ title: "排序成本", severity: "WARN", confidence: "MEDIUM", impact: "可能额外排序", precondition: "确认返回行数", evidenceRefs: ["E_EXPLAIN"] }],
    rewriteCandidates: [{ sql: "select id from orders where status = ?", change: "缩小投影", semanticCheck: "WHERE 保持一致", risk: "确认调用方", validation: "对比结果集", evidenceRefs: ["E_EXPLAIN"] }],
    indexCandidates: [{ tableName: "orders", columnOrder: ["status", "created_at"], ddl: "create index idx_orders_status_created on orders(status, created_at)", benefit: "减少排序", writeCost: "增加写成本", risk: "写多场景评估", validation: "EXPLAIN", confidence: "HIGH", evidenceRefs: ["E_EXPLAIN"] }],
    validationPlan: [{ action: "执行 EXPLAIN", expectedSignal: "命中新索引", evidenceRefs: ["E_EXPLAIN"] }],
    missingInformation: ["调用频次"],
    safetyWarnings: ["灰度验证后再上线"],
    review: { verdict: "PASS", notes: "复核通过", revisions: [] },
    findings: [],
    rewriteSql: "",
    indexSuggestions: [],
    validationSteps: [],
    riskWarnings: [],
    needMoreInfo: [],
    rawModelOutput: "",
    mockModel: false
  }
};

describe("TuningEvidenceDetails", () => {
  it("keeps the full validated audit reachable outside the concise chat answer", async () => {
    render(<TuningEvidenceDetails task={task} />);

    expect(screen.getByText("审计详情")).toBeInTheDocument();
    await userEvent.click(screen.getByText("证据目录 (1)"));
    expect(screen.getByText("E_EXPLAIN · USER_EXPLAIN")).toBeInTheDocument();
    await userEvent.click(screen.getByText("诊断 (1)"));
    expect(screen.getByText(/排序成本/)).toBeInTheDocument();
    await userEvent.click(screen.getByText("改写候选 (1)"));
    expect(screen.getByText(/select id from orders/)).toBeInTheDocument();
    await userEvent.click(screen.getByText("索引候选 (1)"));
    expect(screen.getByText(/idx_orders_status_created/)).toBeInTheDocument();
    await userEvent.click(screen.getByText("验证与风险"));
    expect(screen.getByText("灰度验证后再上线")).toBeInTheDocument();
    expect(screen.getByText("深度复核：PASS")).toBeInTheDocument();
    await userEvent.click(screen.getByText("规则与链路 (1 / 1)"));
    expect(screen.getByText("ruleCheck")).toBeInTheDocument();
  });
});
