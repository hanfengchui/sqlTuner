import type { TaskStatus } from "../types/api";

export const statusLabel: Record<TaskStatus, string> = {
  RECEIVED: "已接收",
  SANITIZED: "已脱敏",
  RULE_CHECKED: "规则扫描",
  LLM_ANALYZING: "模型分析",
  REVIEWING: "复核建议",
  DONE: "完成",
  FAILED: "失败"
};

export const statusOrder: TaskStatus[] = [
  "RECEIVED",
  "SANITIZED",
  "RULE_CHECKED",
  "LLM_ANALYZING",
  "REVIEWING",
  "DONE"
];
