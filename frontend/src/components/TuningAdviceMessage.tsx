import { AlertTriangle, CheckCircle2, Loader2 } from "lucide-react";
import { useLayoutEffect, useMemo } from "react";
import type {
  AnalysisNarrativeSection,
  Diagnosis,
  IndexCandidate,
  ModelStreamUpdate,
  RewriteCandidate,
  SqlTuningTask,
  ValidationStep
} from "../types/api";
import {
  AdviceBasisBlock,
  MissingAdviceBlock,
  NarrativeSectionView,
  RecommendationBlock,
  ValidationPlanView,
  WarningAdviceBlock,
  stripEvidenceIds
} from "./TuningAdviceContent";

interface TuningAdviceMessageProps {
  task?: SqlTuningTask;
  onContentChange?: () => void;
}

type AdviceBlock =
  | { id: "basis"; type: "basis" }
  | { id: string; type: "narrative"; section: AnalysisNarrativeSection }
  | { id: "rewrite"; type: "rewrite"; candidate: RewriteCandidate }
  | { id: "index"; type: "index"; candidate: IndexCandidate }
  | { id: "validation-plan"; type: "validation-plan"; plan: Array<ValidationStep | string> }
  | { id: "missing"; type: "missing" }
  | { id: "warning"; type: "warning" };

export function TuningAdviceMessage({ task, onContentChange }: TuningAdviceMessageProps) {
  const advice = useMemo(() => normalizeAdvice(task), [task]);
  const blocks = useMemo(() => buildBlocks(advice), [advice]);

  useLayoutEffect(() => {
    onContentChange?.();
  }, [blocks.length, onContentChange, task?.version]);

  if (!task?.result) {
    return <TaskProgressMessage task={task} />;
  }

  return (
    <article className="tuning-advice" aria-live="polite">
      <header className="assistant-message-header advice-message-status">
        <span className={advice.outcome === "NEEDS_INPUT" ? "advice-state needs-input" : "advice-state"}>
          {advice.outcome === "NEEDS_INPUT" ? <AlertTriangle size={14} /> : <CheckCircle2 size={14} />}
          {advice.outcome === "NEEDS_INPUT" ? "需要补充" : "已校验"}
        </span>
      </header>

      <section className="advice-conclusion">
        <p>{stripConclusionPrefix(advice.narrative?.conclusion || advice.summary)}</p>
      </section>

      {blocks.map((block) => renderBlock(block, advice))}
    </article>
  );
}

export function TaskProgressMessage({ task, stream }: { task?: SqlTuningTask; stream?: ModelStreamUpdate }) {
  const failed = task?.status === "FAILED";
  const completed = task?.status === "DONE";
  const message = task?.statusMessage || "正在准备调优任务";
  const draftText = safeStreamingDraft(stream?.draftText);

  if (!failed && !completed && draftText) {
    return (
      <article className="assistant-streaming-draft" aria-live="polite">
        <header>
          <Loader2 className="spin" size={15} />
          <span>正在生成 · 待校验</span>
        </header>
        <p>{draftText}</p>
      </article>
    );
  }

  return (
    <article className={failed ? "assistant-progress failed" : "assistant-progress"} aria-live="polite">
      <div className="assistant-progress-icon">
        {failed ? <AlertTriangle size={18} /> : completed ? <CheckCircle2 size={18} /> : <Loader2 className="spin" size={18} />}
      </div>
      <div>
        <p>{failed ? "本轮分析没有完成。请检查输入后重新发送。" : completed ? "正在整理已校验的调优建议。" : message}</p>
        {!failed && !completed && stream && stream.receivedChars > 0 && (
          <span>{stream.phase === "THINKING" ? "模型正在推理" : "模型正在输出"} · 已接收 {stream.receivedChars.toLocaleString("zh-CN")} 字符</span>
        )}
      </div>
    </article>
  );
}

function safeStreamingDraft(value?: string) {
  if (!value?.trim()) {
    return "";
  }
  const normalized = value.replace(/\u0000/g, "").trim();
  if (normalized.startsWith("{") || /"(?:indexCandidates|rewriteCandidates|rawModelOutput)"\s*:/.test(normalized)) {
    return "";
  }
  if (/(?:```\s*sql\b|\bsql\s*:|\b(?:select|with|insert|replace|update|delete|merge|create|alter|drop|truncate|rename|grant|revoke|call|exec|execute|begin|declare|explain|analyze|lock|set|commit|rollback)\b)/i.test(normalized)) {
    return "";
  }
  return normalized;
}

function renderBlock(block: AdviceBlock, advice: ReturnType<typeof normalizeAdvice>) {
  switch (block.type) {
    case "basis":
      return <AdviceBasisBlock key={block.id} items={advice.basis} />;
    case "narrative":
      return <NarrativeSectionView key={block.id} section={block.section} />;
    case "rewrite":
      return <RecommendationBlock key={block.id} type="rewrite" candidate={block.candidate} />;
    case "index":
      return <RecommendationBlock key={block.id} type="index" candidate={block.candidate} />;
    case "validation-plan":
      return <ValidationPlanView key={block.id} plan={block.plan} />;
    case "missing":
      return <MissingAdviceBlock key={block.id} message={advice.missing} />;
    case "warning":
      return <WarningAdviceBlock key={block.id} message={advice.warning} />;
  }
}

function buildBlocks(advice: ReturnType<typeof normalizeAdvice>): AdviceBlock[] {
  const blocks: AdviceBlock[] = [];
  const sections = advice.narrative?.sections || [];
  const evidence = firstSection(sections, "EVIDENCE");
  const action = firstSection(sections, "ACTION");
  const validation = firstSection(sections, "VALIDATION");
  const caution = firstSection(sections, "CAUTION");

  if (evidence) {
    blocks.push({ id: "narrative-evidence", type: "narrative", section: evidence });
  } else if (advice.basis.length > 0) {
    blocks.push({ id: "basis", type: "basis" });
  }

  if (action) {
    blocks.push({ id: "narrative-action", type: "narrative", section: action });
  }
  appendRecommendation(blocks, advice);

  if (validation) {
    blocks.push({ id: "narrative-validation", type: "narrative", section: validation });
  } else if (advice.validationPlan.length > 0) {
    blocks.push({ id: "validation-plan", type: "validation-plan", plan: advice.validationPlan });
  }

  if (advice.outcome === "NEEDS_INPUT") {
    blocks.push({ id: "missing", type: "missing" });
  }

  if (caution) {
    blocks.push({ id: "narrative-caution", type: "narrative", section: caution });
  } else if (advice.warning) {
    blocks.push({ id: "warning", type: "warning" });
  }
  return blocks;
}

function appendRecommendation(blocks: AdviceBlock[], advice: ReturnType<typeof normalizeAdvice>) {
  if (advice.outcome !== "ADVICE") {
    return;
  }
  if (advice.index?.ddl?.trim()) {
    blocks.push({ id: "index", type: "index", candidate: advice.index });
    return;
  }
  if (advice.rewrite && (advice.rewrite.sql || advice.rewrite.rewrittenSql)) {
    blocks.push({ id: "rewrite", type: "rewrite", candidate: advice.rewrite });
    return;
  }
  if (advice.index && (advice.index.benefit || advice.index.columnOrder?.length || advice.index.columns?.length)) {
    blocks.push({ id: "index", type: "index", candidate: advice.index });
  }
}

function normalizeAdvice(task?: SqlTuningTask) {
  const result = task?.result;
  const diagnoses: Diagnosis[] = result?.diagnoses || result?.findings?.map((finding) => ({
    title: finding.title,
    severity: "INFO",
    impact: finding.impact || finding.evidence,
    confidence: finding.confidence
  })) || [];
  const rewrites: RewriteCandidate[] = result?.rewriteCandidates || (result?.rewriteSql
    ? [{ title: "建议改写", sql: result.rewriteSql }]
    : []);
  const indexes: IndexCandidate[] = result?.indexCandidates || result?.indexSuggestions?.map((suggestion) => ({
    tableName: suggestion.indexName,
    columnOrder: suggestion.columns,
    benefit: suggestion.benefit,
    risk: suggestion.risk,
    validation: suggestion.validation
  })) || [];
  const outcome = result?.outcome || task?.outcome || "ADVICE";
  const missing = result?.missingInformation || result?.needMoreInfo || [];
  const structuredValidation = result?.validationPlan || [];
  const legacyValidation = result?.validationSteps || [];
  const candidateValidation = indexes[0]?.validation || rewrites[0]?.validation || rewrites[0]?.validationSteps?.[0] || "";
  const validationPlan: Array<ValidationStep | string> = structuredValidation.length > 0
    ? structuredValidation
    : legacyValidation.length > 0
      ? legacyValidation
      : candidateValidation ? [candidateValidation] : [];
  const firstMissing = stripEvidenceIds(missing[0] || "");

  return {
    outcome,
    narrative: result?.analysisNarrative,
    summary: result?.summary || (task?.status === "FAILED" ? "任务失败，请检查输入或稍后重试。" : "本轮分析已完成。"),
    basis: diagnosisBasis(diagnoses),
    rewrite: rewrites[0],
    index: indexes[0],
    validationPlan,
    missing: firstMissing ? `请补充：${firstMissing}` : "请补充会改变下一步动作的结构或执行计划信息。",
    warning: stripEvidenceIds((result?.safetyWarnings || result?.riskWarnings || [])[0] || "")
  };
}

function firstSection(sections: AnalysisNarrativeSection[], kind: string) {
  return sections.find((section) => section.kind?.toUpperCase() === kind);
}

function stripConclusionPrefix(value: string) {
  return stripEvidenceIds(value).replace(/^(?:最终结论|结论)\s*[：:]\s*/, "");
}

function diagnosisBasis(diagnoses: Diagnosis[]) {
  return diagnoses.slice(0, 3).map((diagnosis) => {
    const title = stripEvidenceIds(diagnosis.title || "SQL 诊断");
    const detail = stripEvidenceIds(diagnosis.impact || "");
    return { title, detail: detail && detail !== title ? detail : undefined };
  });
}
