export type UserRole = "ADMIN" | "USER";

export type SqlDialect = "OceanBase MySQL" | "OceanBase Oracle";

export interface PlanImage {
  name: string;
  dataUrl: string;
}

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

export interface ConversationPage {
  items: Conversation[];
  nextBefore?: number;
  hasMore: boolean;
}

export interface Message {
  id: number;
  conversationId: number;
  role: "USER" | "ASSISTANT";
  content: string;
  taskId?: number;
  createdAt: string;
}

export interface ConversationTimelineItem {
  message: Message;
  task?: SqlTuningTask | null;
}

export interface ConversationTimeline {
  items: ConversationTimelineItem[];
  nextBefore?: number;
  hasMore: boolean;
}

export type TaskStatus =
  | "QUEUED"
  | "RECEIVED"
  | "CONTEXT_CHECKED"
  | "SANITIZED"
  | "RULE_CHECKED"
  | "LLM_ANALYZING"
  | "VERIFYING"
  | "REVIEWING"
  | "DONE"
  | "FAILED";

export type AdviceOutcome = "ADVICE" | "NEEDS_INPUT";
export type Confidence = "LOW" | "MEDIUM" | "HIGH" | string;

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

export interface ContextAssessment {
  completeness?: "SQL_ONLY" | "SQL_SCHEMA" | "SQL_SCHEMA_INDEX" | "FULL_EVIDENCE" | string;
  maxConfidence?: Confidence;
  availableEvidence?: string[];
  missingInformation?: string[];
  policyNotes?: string[];
  summary?: string;
  // Legacy compatibility for one release while older task artifacts exist.
  confidenceCeiling?: Confidence;
}

export interface EvidenceItem {
  id: string;
  source: string;
  summary: string;
  trustLevel?: Confidence;
  // Legacy compatibility for one release while older task artifacts exist.
  reliability?: Confidence;
}

export interface Diagnosis {
  title?: string;
  severity?: "INFO" | "WARN" | "HIGH" | "CRITICAL" | string;
  evidenceRefs?: string[];
  impact?: string;
  confidence?: Confidence;
  precondition?: string;
  // Legacy compatibility for one release while older task artifacts exist.
  preconditions?: string[];
}

export interface RewriteCandidate {
  title?: string;
  sql?: string;
  change?: string;
  semanticCheck?: string;
  risk?: string;
  validation?: string;
  evidenceRefs?: string[];
  // Legacy compatibility for one release while older task artifacts exist.
  rewrittenSql?: string;
  changes?: string[];
  semanticChecks?: string[];
  risks?: string[];
  validationSteps?: string[];
}

export interface IndexCandidate {
  tableName?: string;
  columnOrder?: string[];
  ddl?: string;
  benefit?: string;
  writeCost?: string;
  risk?: string;
  validation?: string;
  confidence?: Confidence;
  evidenceRefs?: string[];
  // Legacy compatibility for one release while older task artifacts exist.
  table?: string;
  columns?: string[];
  risks?: string[];
  validationMethod?: string;
}

export interface ReviewResult {
  verdict?: "PASS" | "REVISE" | "REJECT" | string;
  notes?: string;
  revisions?: string[];
}

export interface ValidationStep {
  action?: string;
  expectedSignal?: string;
  evidenceRefs?: string[];
}

export interface AnalysisNarrativeSection {
  kind: "CONCLUSION" | "EVIDENCE" | "CAUTION" | "ACTION" | "VALIDATION" | string;
  title: string;
  body: string;
  evidenceRefs?: string[];
}

export interface AnalysisNarrative {
  conclusion: string;
  sections: AnalysisNarrativeSection[];
}

export interface TuningResult {
  outcome?: AdviceOutcome;
  analysisNarrative?: AnalysisNarrative;
  contextAssessment?: ContextAssessment;
  evidenceCatalog?: EvidenceItem[];
  diagnoses?: Diagnosis[];
  rewriteCandidates?: RewriteCandidate[];
  indexCandidates?: IndexCandidate[];
  validationPlan?: Array<ValidationStep | string>;
  missingInformation?: string[];
  safetyWarnings?: string[];
  review?: ReviewResult;
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

export interface ModelStreamUpdate {
  phase: "THINKING" | "ANSWER" | "VERIFYING" | string;
  draftText?: string;
  receivedChars: number;
  sequence: number;
  attempt?: number;
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
  obVersion?: string;
  tableStatsText?: string;
  runtimeMetricsText?: string;
  businessInvariants?: string;
  allowedActions?: string[];
  queuePosition?: number;
  version?: number;
  attemptCount?: number;
  outcome?: AdviceOutcome;
  contextAssessment?: ContextAssessment;
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
  visionModel: string;
  timeoutMs?: number;
  apiKeyConfigured: boolean;
  mockState?: "mock" | "ready" | "missing-key" | string;
}

export interface ModelPreviewResult {
  success: boolean;
  provider: string;
  model: string;
  mock: boolean;
  elapsedMs: number;
  output?: string;
  message: string;
}

export interface ModelProviderOption {
  value: string;
  label: string;
  defaultBaseUrl: string;
  defaultModel: string;
  requiresApiKey: boolean;
}

export interface ModelCatalogView {
  endpoint: string;
  models: string[];
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

export interface RuntimeHealthView {
  provider: string;
  model: string;
  mockState: "mock" | "ready" | "missing-key" | string;
  apiKeyConfigured: boolean;
  mysql?: string;
  scheduler?: string;
  queued?: number;
  running?: number;
  retentionEnabled?: boolean;
  retentionDays?: number;
}

export interface ReadinessView {
  status: string;
  mysql?: string;
  scheduler?: string;
}
