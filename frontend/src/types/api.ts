export type UserRole = "ADMIN" | "USER";

export type SqlDialect = "OceanBase MySQL" | "OceanBase Oracle";

export interface ApiResponse<T> {
  success: boolean;
  message?: string;
  data: T;
}

export interface UserView {
  id: number;
  username: string;
  displayName: string;
  role: UserRole;
}

export interface Conversation {
  id: number;
  userId: number;
  title: string;
  createdAt: string;
  updatedAt: string;
}

export interface Message {
  id: number;
  conversationId: number;
  role: "USER" | "ASSISTANT";
  content: string;
  taskId?: number;
  createdAt: string;
}

export type TaskStatus =
  | "RECEIVED"
  | "SANITIZED"
  | "RULE_CHECKED"
  | "LLM_ANALYZING"
  | "REVIEWING"
  | "DONE"
  | "FAILED";

export interface RuleFinding {
  code: string;
  severity: "INFO" | "WARN" | "HIGH";
  title: string;
  evidence: string;
  suggestion: string;
}

export interface ResultFinding {
  title: string;
  evidence: string;
  impact: string;
  confidence: string;
}

export interface IndexSuggestion {
  indexName: string;
  columns: string[];
  benefit: string;
  risk: string;
  validation: string;
}

export interface TuningResult {
  summary: string;
  findings: ResultFinding[];
  rewriteSql: string;
  indexSuggestions: IndexSuggestion[];
  validationSteps: string[];
  riskWarnings: string[];
  needMoreInfo: string[];
  rawModelOutput: string;
  mockModel: boolean;
}

export interface HarnessArtifact {
  nodeName: string;
  summary: string;
  payload: unknown;
  createdAt: string;
}

export interface SqlTuningTask {
  id: number;
  userId: number;
  conversationId: number;
  dbDialect: string;
  inputType?: "sql" | "natural_language" | string;
  originalSql: string;
  sanitizedSql?: string;
  sqlHash?: string;
  schemaText?: string;
  indexText?: string;
  explainText?: string;
  businessContext?: string;
  deepAnalysis: boolean;
  status: TaskStatus;
  statusMessage: string;
  skillName?: string;
  skillVersion?: number;
  ruleFindings: RuleFinding[];
  result?: TuningResult;
  artifacts: HarnessArtifact[];
  createdAt: string;
  updatedAt: string;
}

export interface SkillVersion {
  id: number;
  name: string;
  version: number;
  content: string;
  enabled: boolean;
  updatedAt: string;
}

export interface ModelConfigView {
  provider: string;
  baseUrl: string;
  model: string;
  timeoutMs?: number;
  apiKeyConfigured: boolean;
}

export interface ModelProviderOption {
  value: string;
  label: string;
  defaultBaseUrl: string;
  defaultModel: string;
  requiresApiKey: boolean;
}

export interface ModelTestResult {
  success: boolean;
  provider: string;
  model: string;
  mock: boolean;
  elapsedMs: number;
  message: string;
  sample?: string;
}
