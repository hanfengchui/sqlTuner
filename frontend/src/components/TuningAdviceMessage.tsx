import { AlertTriangle, CheckCircle2, ClipboardCheck, Copy, FileSearch, Lightbulb, ListChecks, Loader2, ShieldAlert, Sparkles, Wrench } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import type { AnalysisNarrativeSection, Diagnosis, IndexCandidate, RewriteCandidate, SqlTuningTask, ValidationStep } from "../types/api";

interface TuningAdviceMessageProps {
  task?: SqlTuningTask;
  progressive?: boolean;
}

export function TuningAdviceMessage({ task, progressive = true }: TuningAdviceMessageProps) {
  const advice = useMemo(() => normalizeAdvice(task), [task]);
  const sections = useMemo(() => {
    const next: string[] = [];
    if (advice.narrative?.sections.length) {
      advice.narrative.sections.forEach((_, index) => next.push(`narrative-${index}`));
    } else if (advice.diagnoses.length > 0) {
      next.push("diagnoses");
    }
    if (advice.rewrite) {
      next.push("rewrite");
    }
    if (advice.index) {
      next.push("index");
    }
    if ((!advice.narrative || !hasNarrativeKind(advice.narrative.sections, "VALIDATION")) && (advice.nextStep || advice.warning)) {
      next.push("next");
    }
    return next;
  }, [advice.diagnoses.length, advice.index, advice.narrative, advice.nextStep, advice.rewrite, advice.warning]);
  const [visibleSections, setVisibleSections] = useState(progressive ? 0 : sections.length);

  useEffect(() => {
    if (!progressive || prefersReducedMotion()) {
      setVisibleSections(sections.length);
      return;
    }

    setVisibleSections(0);
    const timers = sections.map((_, index) => window.setTimeout(() => {
      setVisibleSections(index + 1);
    }, 140 * (index + 1)));
    return () => timers.forEach((timer) => window.clearTimeout(timer));
  }, [progressive, sections, task?.id, task?.version]);

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

      <p className="advice-summary">{advice.narrative?.conclusion || advice.summary}</p>

      {advice.narrative?.sections.map((section, index) => isVisible(sections, `narrative-${index}`, visibleSections) && (
        <NarrativeSectionView key={`${section.kind}-${section.title}-${index}`} section={section} />
      ))}

      {!advice.narrative && isVisible(sections, "diagnoses", visibleSections) && (
        <section className="advice-section advice-diagnoses">
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
      )}

      {isVisible(sections, "rewrite", visibleSections) && advice.rewrite && (
        <section className="advice-section advice-rewrite">
          <h3><Wrench size={16} />建议改写</h3>
          <SqlSnippet value={advice.rewrite.sql || advice.rewrite.rewrittenSql || ""} />
          {firstText(advice.rewrite.change, advice.rewrite.changes) && <p>{firstText(advice.rewrite.change, advice.rewrite.changes)}</p>}
          {firstText(advice.rewrite.risk, advice.rewrite.risks) && <small>注意：{firstText(advice.rewrite.risk, advice.rewrite.risks)}</small>}
        </section>
      )}

      {isVisible(sections, "index", visibleSections) && advice.index && (
        <section className="advice-section advice-index">
          <h3><FileSearch size={16} />索引方向</h3>
          <strong>{indexTitle(advice.index)}</strong>
          {advice.index.ddl && <SqlSnippet value={advice.index.ddl} />}
          {advice.index.benefit && <p>{advice.index.benefit}</p>}
          {advice.index.writeCost && <small>写入成本：{advice.index.writeCost}</small>}
        </section>
      )}

      {isVisible(sections, "next", visibleSections) && (advice.nextStep || advice.warning) && (
        <section className={advice.outcome === "NEEDS_INPUT" ? "advice-section advice-next needs-input" : "advice-section advice-next"}>
          <h3><ClipboardCheck size={16} />{advice.outcome === "NEEDS_INPUT" ? "下一步" : "验证"}</h3>
          {advice.nextStep && <p>{advice.nextStep}</p>}
          {advice.warning && <small>注意：{advice.warning}</small>}
        </section>
      )}
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

function SqlSnippet({ value }: { value: string }) {
  if (!value) {
    return null;
  }

  async function copy() {
    if (navigator.clipboard) {
      await navigator.clipboard.writeText(value);
    }
  }

  return (
    <div className="advice-sql-snippet">
      <button onClick={copy} type="button" aria-label="复制 SQL" title="复制 SQL">
        <Copy size={15} />
      </button>
      <pre>{value}</pre>
    </div>
  );
}

function NarrativeSectionView({ section }: { section: AnalysisNarrativeSection }) {
  const icon = narrativeIcon(section.kind);
  return (
    <section className={`advice-section advice-narrative advice-narrative-${section.kind.toLowerCase()}`}>
      <h3>{icon}{section.title}</h3>
      <p>{section.body}</p>
    </section>
  );
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
  const validation = firstValidation(result?.validationPlan, result?.validationSteps);

  return {
    outcome,
    narrative: result?.analysisNarrative,
    summary: result?.summary || (task?.status === "FAILED" ? "任务失败，请检查输入或稍后重试。" : "本轮分析已完成。"),
    diagnoses,
    rewrite: rewrites[0],
    index: indexes[0],
    nextStep: outcome === "NEEDS_INPUT"
      ? missing[0] ? `请补充：${missing[0]}` : "请补充可验证的执行计划或表结构信息。"
      : validation,
    warning: (result?.safetyWarnings || result?.riskWarnings || [])[0]
  };
}

function hasNarrativeKind(sections: AnalysisNarrativeSection[], kind: string) {
  return sections.some((section) => section.kind.toUpperCase() === kind);
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

function firstValidation(plan?: Array<ValidationStep | string>, legacy?: string[]) {
  const first = plan?.[0];
  if (typeof first === "string") {
    return first;
  }
  if (first) {
    const details = [first.action, first.expectedSignal].filter(Boolean);
    return details.join("：");
  }
  return legacy?.[0];
}

function firstText(primary?: string, legacy?: string[]) {
  return primary || legacy?.[0] || "";
}

function indexTitle(index: IndexCandidate) {
  const table = index.tableName || index.table || "目标表";
  const columns = index.columnOrder || index.columns || [];
  return columns.length > 0 ? `${table} (${columns.join(", ")})` : table;
}

function isVisible(sections: string[], section: string, visibleSections: number) {
  const index = sections.indexOf(section);
  return index >= 0 && index < visibleSections;
}

function prefersReducedMotion() {
  return typeof window !== "undefined" && window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
}
