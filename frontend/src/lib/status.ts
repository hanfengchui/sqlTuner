import type { TaskStatus } from "../types/api";

export const statusLabel: Record<TaskStatus, string> = {
  QUEUED: "排队中",
  RECEIVED: "已接收",
  CONTEXT_CHECKED: "证据检查",
  SANITIZED: "已脱敏",
  RULE_CHECKED: "规则扫描",
  LLM_ANALYZING: "模型分析",
  VERIFYING: "安全校验",
  REVIEWING: "复核建议",
  DONE: "完成",
  FAILED: "失败"
};

export const statusOrder: TaskStatus[] = [
  "QUEUED",
  "RECEIVED",
  "CONTEXT_CHECKED",
  "SANITIZED",
  "RULE_CHECKED",
  "LLM_ANALYZING",
  "VERIFYING",
  "REVIEWING",
  "DONE"
];
