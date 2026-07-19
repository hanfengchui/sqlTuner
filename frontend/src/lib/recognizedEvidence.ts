import type { SqlTuningTask } from "../types/api";

type EvidenceTask = Pick<SqlTuningTask, "runtimeMetricsText" | "tableStatsText">;

export function formatRecognizedEvidence(task?: EvidenceTask): string {
  if (!task) {
    return "";
  }

  const runtime = task.runtimeMetricsText || "";
  const parts: string[] = [];
  appendMetric(parts, "执行", runtime, /执行次数(?:（报告结论内识别，待核验）)?\s*[:：=]\s*([0-9][0-9,.]*)/i);
  appendMetric(parts, "CPU", runtime, /CPU\s*(?:占比)?(?:（报告结论内识别，待核验）)?\s*[:：=]\s*([0-9][0-9,.]*\s*%?)/i, compactUnit);
  appendMetric(parts, "平均", runtime, /平均耗时(?:（报告结论内识别，待核验）)?\s*[:：=]\s*([0-9][0-9,.]*\s*(?:ns|us|µs|ms|s|毫秒|秒)?)/i, compactUnit);
  appendMetric(parts, "返回", runtime, /平均返回行数(?:（报告结论内识别，待核验）)?\s*[:：=]\s*([0-9][0-9,.]*)\s*(?:行|rows?)?/i, (value) => `${displayNumber(value)} 行`);
  appendMetric(parts, "逻辑读", runtime, /逻辑读(?:取|次数|块数)?(?:（报告结论内识别，待核验）)?\s*[:：=]\s*([0-9][0-9,.]*)(?:\s*(次|页|块|KB|MB|GB))?/i, formatReadMetric);
  appendMetric(parts, "物理读", runtime, /物理读(?:取|次数|块数)?(?:（报告结论内识别，待核验）)?\s*[:：=]\s*([0-9][0-9,.]*)(?:\s*(次|页|块|KB|MB|GB))?/i, formatReadMetric);

  const tableRows = largestTableRowCount(task.tableStatsText || "");
  if (tableRows !== undefined) {
    parts.push(`表规模约 ${compactCount(tableRows)}`);
  }
  return parts.join(" · ");
}

function appendMetric(
  parts: string[],
  label: string,
  source: string,
  pattern: RegExp,
  formatter: (value: string, unit?: string) => string = compactUnit
) {
  const match = pattern.exec(source);
  if (match?.[1]) {
    parts.push(`${label} ${formatter(match[1], match[2])}`);
  }
}

function compactUnit(value: string) {
  return value.replace(/\s+/g, "").replace(/,$/, "");
}

function formatReadMetric(value: string, unit?: string) {
  const count = displayNumber(value);
  return unit ? `${count} ${unit.toUpperCase()}` : count;
}

function displayNumber(value: string) {
  const normalized = value.replace(/,/g, "");
  const number = Number(normalized);
  return Number.isFinite(number) ? number.toLocaleString("zh-CN", { maximumFractionDigits: 6 }) : value;
}

function largestTableRowCount(source: string): number | undefined {
  const counts: number[] = [];
  collectCounts(source, /[:：=]\s*([0-9][0-9,]*)\s*(?:行|rows?)/gi, counts);
  collectCounts(source, /\brows?\s*[:：=]\s*([0-9][0-9,]*)/gi, counts);
  return counts.length > 0 ? Math.max(...counts) : undefined;
}

function collectCounts(source: string, pattern: RegExp, counts: number[]) {
  let match: RegExpExecArray | null;
  while ((match = pattern.exec(source)) !== null) {
    const value = Number(match[1].replace(/,/g, ""));
    if (Number.isFinite(value)) {
      counts.push(value);
    }
  }
}

function compactCount(value: number) {
  if (value >= 100_000_000) {
    return `${trimDecimal(value / 100_000_000)} 亿`;
  }
  if (value >= 10_000) {
    return `${Math.round(value / 10_000)} 万`;
  }
  return value.toLocaleString("zh-CN");
}

function trimDecimal(value: number) {
  return value.toFixed(1).replace(/\.0$/, "");
}
