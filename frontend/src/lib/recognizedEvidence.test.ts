import { describe, expect, it } from "vitest";
import { formatRecognizedEvidence } from "./recognizedEvidence";

describe("formatRecognizedEvidence", () => {
  it("formats the high-value facts from the pasted diagnostic report", () => {
    expect(formatRecognizedEvidence({
      runtimeMetricsText: [
        "SQL ID: B05FC9141039983E7E33ECD3A563E37D",
        "执行次数: 21.88",
        "CPU 占比: 41.7%",
        "平均耗时: 2008ms",
        "平均返回行数（报告结论内识别，待核验）: 1 行"
      ].join("\n"),
      tableStatsText: "GRP_CUSTOMER: 2294867 行\nGRP_CUSTOMER_EX: 2294758 行"
    })).toBe("执行 21.88 · CPU 41.7% · 平均 2008ms · 返回 1 行 · 表规模约 229 万");
  });

  it("keeps logical and physical reads as separate facts", () => {
    expect(formatRecognizedEvidence({
      runtimeMetricsText: "逻辑读（报告结论内识别，待核验）: 123456 次\n物理读（报告结论内识别，待核验）: 789",
      tableStatsText: "orders rows=8000"
    })).toBe("逻辑读 123,456 次 · 物理读 789 · 表规模约 8,000");
  });

  it("returns an empty receipt when no supported evidence was recognized", () => {
    expect(formatRecognizedEvidence({ runtimeMetricsText: "SQL ID: ONLY-ID", tableStatsText: "" })).toBe("");
  });
});
