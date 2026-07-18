import { AlertTriangle, CheckCircle2, ClipboardCheck, Copy, FileSearch, Lightbulb, ListChecks, Loader2, ShieldAlert, Sparkles, Wrench } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import type { AnalysisNarrativeSection, Diagnosis, IndexCandidate, RewriteCandidate, SqlTuningTask, ValidationStep } from "../types/api";

interface TuningAdviceMessageProps {
  task?: SqlTuningTask;
  progressive?: boolean;
}

type AdviceBlock =
  | { id: string; type: "narrative"; section: AnalysisNarrativeSection }
  | { id: "diagnoses"; type: "diagnoses" }
  | { id: "rewrite"; type: "rewrite"; candidate: RewriteCandidate; compact?: boolean }
  | { id: "index"; type: "index"; candidate: IndexCandidate; compact?: boolean }
  | { id: "validation-plan"; type: "validation-plan"; plan: Array<ValidationStep | string> }
  | { id: "next"; type: "next" };

export function TuningAdviceMessage({ task, progressive = true }: TuningAdviceMessageProps) {
  const advice = useMemo(() => normalizeAdvice(task), [task]);
  const blocks = useMemo(() => buildBlocks(advice), [advice]);
  const [visibleBlockCount, setVisibleBlockCount] = useState(progressive ? 0 : blocks.length);

  useEffect(() => {
    if (!progressive || prefersReducedMotion()) {
      setVisibleBlockCount(blocks.length);
      return;
    }

    setVisibleBlockCount(0);
    const timers = blocks.map((_, index) => window.setTimeout(() => {
      setVisibleBlockCount(index + 1);
    }, 140 * (index + 1)));
    return () => timers.forEach((timer) => window.clearTimeout(timer));
  }, [blocks, progressive, task?.id, task?.version]);

  if (!task?.result) {
    return <TaskProgressMessage task={task} />;
  }

  return (
    <article className="tuning-advice" aria-live="polite">
      <header className="assistant-message-header">
        <div>
          <Sparkles size={17} />
          <strong>SQL 调优助手</strong>
        </div>
        <span className={advice.outcome === "NEEDS_INPUT" ? "advice-state needs-input" : "advice-state"}>
          {advice.outcome === "NEEDS_INPUT" ? "需要补充" : "已校验"}
        </span>
      </header>

      <section className="advice-conclusion">
        <p><strong>最终结论：</strong>{stripConclusionPrefix(advice.narrative?.conclusion || advice.summary)}</p>
      </section>

      {blocks.slice(0, visibleBlockCount).map((block) => renderBlock(block, advice))}
    </article>
  );
}

export function TaskProgressMessage({ task }: { task?: SqlTuningTask }) {
  const failed = task?.status === "FAILED";
  const completed = task?.status === "DONE";
  const message = task?.statusMessage || "正在准备调优任务";

  return (
    <article className={failed ? "assistant-progress failed" : "assistant-progress"} aria-live="polite">
      <div className="assistant-progress-icon">
        {failed ? <AlertTriangle size={18} /> : completed ? <CheckCircle2 size={18} /> : <Loader2 className="spin" size={18} />}
      </div>
      <div>
        <strong>SQL 调优助手</strong>
        <p>{failed ? "本轮分析没有完成。请检查输入后重新发送。" : completed ? "正在整理已校验的调优建议。" : message}</p>
      </div>
    </article>
  );
}

function renderBlock(block: AdviceBlock, advice: ReturnType<typeof normalizeAdvice>) {
  switch (block.type) {
    case "narrative":
      return <NarrativeSectionView key={block.id} section={block.section} />;
    case "diagnoses":
      return (
        <section key={block.id} className="advice-block advice-diagnoses">
          <h3><AlertTriangle size={16} />重点问题</h3>
          <ol>
            {advice.diagnoses.slice(0, 3).map((diagnosis, index) => (
              <li key={`${diagnosis.title}-${index}`}>
                <strong>{diagnosis.title || "SQL 诊断"}</strong>
                {diagnosis.impact && <span>{diagnosis.impact}</span>}
              </li>
            ))}
          </ol>
        </section>
      );
    case "rewrite":
      return <RecommendationBlock key={block.id} type="rewrite" candidate={block.candidate} compact={block.compact} />;
    case "index":
      return <RecommendationBlock key={block.id} type="index" candidate={block.candidate} compact={block.compact} />;
    case "validation-plan":
      return <ValidationPlanView key={block.id} plan={block.plan} />;
    case "next":
      return (
        <section key={block.id} className={advice.outcome === "NEEDS_INPUT" ? "advice-block advice-next needs-input" : "advice-block advice-next"}>
          <h3><ClipboardCheck size={16} />{advice.outcome === "NEEDS_INPUT" ? "下一步" : "验证"}</h3>
          {advice.nextStep && <p>{advice.nextStep}</p>}
          {advice.warning && <small>注意：{advice.warning}</small>}
        </section>
      );
  }
}

function buildBlocks(advice: ReturnType<typeof normalizeAdvice>): AdviceBlock[] {
  const blocks: AdviceBlock[] = [];
  const sections = advice.narrative?.sections || [];

  if (sections.length === 0) {
    if (advice.diagnoses.length > 0) {
      blocks.push({ id: "diagnoses", type: "diagnoses" });
    }
    appendRecommendations(blocks, advice);
    if (advice.validationPlan.length > 0) {
      blocks.push({ id: "validation-plan", type: "validation-plan", plan: advice.validationPlan });
    }
    if (advice.nextStep || advice.warning) {
      blocks.push({ id: "next", type: "next" });
    }
    return blocks;
  }

  const orderedKinds = ["EVIDENCE", "ACTION", "VALIDATION", "CAUTION"];
  const known = new Set<string>();
  let recommendationsAdded = false;
  let hasValidation = false;
  let hasCaution = false;

  orderedKinds.forEach((kind) => {
    sections.forEach((section, index) => {
      if (section.kind.toUpperCase() !== kind) {
        return;
      }
      known.add(`${section.kind}-${index}`);
      blocks.push({ id: `narrative-${kind}-${index}`, type: "narrative", section });
      if (kind === "ACTION" && !recommendationsAdded) {
        appendRecommendations(blocks, advice, true);
        recommendationsAdded = true;
      }
      hasValidation = hasValidation || kind === "VALIDATION";
      hasCaution = hasCaution || kind === "CAUTION";
    });
  });

  if (!recommendationsAdded) {
    appendRecommendations(blocks, advice);
  }
  if (!hasValidation && advice.validationPlan.length > 0) {
    blocks.push({ id: "validation-plan", type: "validation-plan", plan: advice.validationPlan });
  }
  if (advice.outcome === "NEEDS_INPUT" || (!hasCaution && advice.warning)) {
    blocks.push({ id: "next", type: "next" });
  }

  sections.forEach((section, index) => {
    if (section.kind.toUpperCase() !== "CONCLUSION" && !known.has(`${section.kind}-${index}`)) {
      blocks.push({ id: `narrative-other-${index}`, type: "narrative", section });
    }
  });
  return blocks;
}

function appendRecommendations(blocks: AdviceBlock[], advice: ReturnType<typeof normalizeAdvice>, compact = false) {
  if (advice.outcome !== "ADVICE") {
    return;
  }
  if (advice.index?.ddl?.trim()) {
    blocks.push({ id: "index", type: "index", candidate: advice.index, compact });
    return;
  }
  if (advice.rewrite && (advice.rewrite.sql || advice.rewrite.rewrittenSql)) {
    blocks.push({ id: "rewrite", type: "rewrite", candidate: advice.rewrite, compact });
    return;
  }
  if (advice.index && (advice.index.ddl || advice.index.benefit || advice.index.columnOrder?.length || advice.index.columns?.length)) {
    blocks.push({ id: "index", type: "index", candidate: advice.index });
  }
}

function RecommendationBlock({ type, candidate, compact = false }: { type: "rewrite" | "index"; candidate: RewriteCandidate | IndexCandidate; compact?: boolean }) {
  const isIndex = type === "index";
  const index = candidate as IndexCandidate;
  const rewrite = candidate as RewriteCandidate;
  const sql = isIndex ? index.ddl : (rewrite.sql || rewrite.rewrittenSql);
  const title = isIndex ? "索引候选" : "建议改写";
  const description = isIndex ? index.benefit : firstText(rewrite.change, rewrite.changes);
  const risk = isIndex ? index.risk : firstText(rewrite.risk, rewrite.risks);
  const className = `${isIndex ? "advice-block advice-recommendation advice-index" : "advice-block advice-recommendation advice-rewrite"}${compact ? " compact" : ""}`;

  return (
    <section className={className}>
      {!compact && <h3>{isIndex ? <FileSearch size={16} /> : <Wrench size={16} />}{title}</h3>}
      {isIndex && !compact && <strong className="advice-index-title">{indexTitle(index)}</strong>}
      {description && !compact && <p>{description}</p>}
      {sql && <SqlSnippet value={sql} />}
      {isIndex && index.writeCost && <small>写入成本：{index.writeCost}</small>}
      {risk && <small>注意：{risk}</small>}
    </section>
  );
}

function ValidationPlanView({ plan }: { plan: Array<ValidationStep | string> }) {
  const entries = plan.map((item) => typeof item === "string"
    ? item
    : [item.action, item.expectedSignal].filter(Boolean).join("：")
  ).filter(Boolean);
  if (entries.length === 0) {
    return null;
  }
  return (
    <section className="advice-block advice-validation-plan">
      <h3><ListChecks size={16} />验证信号</h3>
      <ul className="advice-list">
        {entries.slice(0, 3).map((entry, index) => <li key={`${entry}-${index}`}><InlineText value={entry} /></li>)}
      </ul>
    </section>
  );
}

function SqlSnippet({ value }: { value: string }) {
  async function copy() {
    if (navigator.clipboard) {
      await navigator.clipboard.writeText(value);
    }
  }

  return (
    <div className="advice-sql-snippet">
      <span>sql</span>
      <button onClick={copy} type="button" aria-label="复制 SQL" title="复制 SQL">
        <Copy size={15} />
      </button>
      <pre>{value}</pre>
    </div>
  );
}

function NarrativeSectionView({ section }: { section: AnalysisNarrativeSection }) {
  return (
    <section className={`advice-block advice-narrative advice-narrative-${section.kind.toLowerCase()}`}>
      <h3>{narrativeIcon(section.kind)}{section.title}</h3>
      <NarrativeBody value={section.body} />
    </section>
  );
}

function NarrativeBody({ value }: { value: string }) {
  return (
    <div className="advice-copy">
      {splitReadableBlocks(value).map((block, index) => block.type === "unordered" ? (
        <ul key={`${block.type}-${index}`} className="advice-list">
          {block.lines.map((line, lineIndex) => <li key={`${line}-${lineIndex}`}><InlineText value={line} /></li>)}
        </ul>
      ) : block.type === "ordered" ? (
        <ol key={`${block.type}-${index}`} className="advice-list ordered">
          {block.lines.map((line, lineIndex) => <li key={`${line}-${lineIndex}`}><InlineText value={line} /></li>)}
        </ol>
      ) : (
        <p key={`${block.type}-${index}`}><InlineText value={block.lines.join(" ")} /></p>
      ))}
    </div>
  );
}

function InlineText({ value }: { value: string }) {
  return (
    <>
      {value.split(/(`[^`]+`|\*\*[^*]+\*\*)/g).filter(Boolean).map((part, index) => {
        if (part.startsWith("`") && part.endsWith("`")) {
          return <code key={index}>{part.slice(1, -1)}</code>;
        }
        if (part.startsWith("**") && part.endsWith("**")) {
          return <strong key={index}>{part.slice(2, -2)}</strong>;
        }
        return part;
      })}
    </>
  );
}

function splitReadableBlocks(value: string) {
  const blocks: Array<{ type: "paragraph" | "unordered" | "ordered"; lines: string[] }> = [];
  let current: { type: "paragraph" | "unordered" | "ordered"; lines: string[] } | undefined;
  const flush = () => {
    if (current?.lines.length) {
      blocks.push(current);
    }
    current = undefined;
  };

  value.split(/\r?\n/).forEach((rawLine) => {
    const line = rawLine.trim();
    if (!line) {
      flush();
      return;
    }
    const ordered = line.match(/^\d+[.)、]\s*(.+)$/);
    const unordered = line.match(/^(?:[-*•])\s*(.+)$/);
    const type = ordered ? "ordered" : unordered ? "unordered" : "paragraph";
    const text = ordered?.[1] || unordered?.[1] || line;
    if (!current || current.type !== type) {
      flush();
      current = { type, lines: [] };
    }
    current.lines.push(text);
  });
  flush();
  return blocks;
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
  const validationPlan = result?.validationPlan || result?.validationSteps || [];

  return {
    outcome,
    narrative: result?.analysisNarrative,
    summary: result?.summary || (task?.status === "FAILED" ? "任务失败，请检查输入或稍后重试。" : "本轮分析已完成。"),
    diagnoses,
    rewrite: rewrites[0],
    index: indexes[0],
    validationPlan,
    nextStep: outcome === "NEEDS_INPUT"
      ? missing[0] ? `请补充：${missing[0]}` : "请补充可验证的执行计划或表结构信息。"
      : firstValidation(validationPlan),
    warning: (result?.safetyWarnings || result?.riskWarnings || [])[0]
  };
}

function stripConclusionPrefix(value: string) {
  return value.replace(/^(?:最终结论|结论)\s*[：:]\s*/, "");
}

function narrativeIcon(kind: string) {
  switch (kind.toUpperCase()) {
    case "EVIDENCE":
      return <FileSearch size={16} />;
    case "CAUTION":
      return <ShieldAlert size={16} />;
    case "ACTION":
      return <Lightbulb size={16} />;
    case "VALIDATION":
      return <ListChecks size={16} />;
    default:
      return <Sparkles size={16} />;
  }
}

function firstValidation(plan?: Array<ValidationStep | string>) {
  const first = plan?.[0];
  if (typeof first === "string") {
    return first;
  }
  if (first) {
    return [first.action, first.expectedSignal].filter(Boolean).join("：");
  }
  return "";
}

function firstText(primary?: string, legacy?: string[]) {
  return primary || legacy?.[0] || "";
}

function indexTitle(index: IndexCandidate) {
  const table = index.tableName || index.table || "目标表";
  const columns = index.columnOrder || index.columns || [];
  return columns.length > 0 ? `${table} (${columns.join(", ")})` : table;
}

function prefersReducedMotion() {
  return typeof window !== "undefined" && window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
}
