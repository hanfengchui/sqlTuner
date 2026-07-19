import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { Message, SqlTuningTask } from "../types/api";
import { ConversationStream } from "./ConversationStream";

vi.stubGlobal("requestAnimationFrame", (callback: FrameRequestCallback) => {
  callback(0);
  return 1;
});

const report = [
  "SQL ID: B05FC9141039983E7E33ECD3A563E37D",
  "SQL: SELECT * FROM GRP_CUSTOMER WHERE EC_CODE = ?",
  "执行次数: 21.88",
  "CPU占比: 41.7%",
  "平均耗时: 2008ms",
  "根因: 平均返回行数仅1行。"
].join("\n");

const message: Message = {
  id: 1,
  conversationId: 2,
  role: "USER",
  content: report,
  taskId: 9,
  createdAt: "2026-07-19T09:00:00Z"
};

const task: SqlTuningTask = {
  id: 9,
  userId: 1,
  conversationId: 2,
  dbDialect: "OceanBase Oracle",
  originalSql: "SELECT * FROM GRP_CUSTOMER WHERE EC_CODE = ?",
  runtimeMetricsText: "执行次数: 21.88\nCPU 占比: 41.7%\n平均耗时: 2008ms\n平均返回行数（报告结论内识别，待核验）: 1 行",
  tableStatsText: "GRP_CUSTOMER: 2294867 行",
  deepAnalysis: false,
  status: "QUEUED",
  statusMessage: "已收到调优请求",
  ruleFindings: [],
  artifacts: [],
  createdAt: "2026-07-19T09:00:00Z",
  updatedAt: "2026-07-19T09:00:00Z"
};

describe("ConversationStream", () => {
  it("keeps the complete pasted report and adds one quiet evidence receipt", () => {
    const { container } = render(<ConversationStream messages={[message]} tasksById={{ 9: task }} />);

    expect(container.querySelector(".user-sql-message")).toHaveTextContent(report, { normalizeWhitespace: false });
    expect(screen.getByText("已识别证据：")).toBeInTheDocument();
    expect(screen.getByText("执行 21.88 · CPU 41.7% · 平均 2008ms · 返回 1 行 · 表规模约 229 万")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /证据/ })).not.toBeInTheDocument();
  });
});
