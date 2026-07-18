import { AlertTriangle, CheckCircle2, Copy, Loader2, Sparkles, Wrench } from "lucide-react";
import { useLayoutEffect, useMemo } from "react";
import type { Diagnosis, IndexCandidate, ModelStreamUpdate, RewriteCandidate, SqlTuningTask } from "../types/api";

interface TuningAdviceMessageProps {
  task?: SqlTuningTask;
  onContentChange?: () => void;
}

type AdviceBlock =
  | { id: "diagnoses"; type: "diagnoses" }
  | { id: "rewrite"; type: "rewrite"; candidate: RewriteCandidate; compact?: boolean }
  | { id: "index"; type: "index"; candidate: IndexCandidate; compact?: boolean }
  | { id: "next"; type: "next" };

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
  if (/(?:\bcreate\s+(?:or\s+replace\s+)?(?:(?:unique|bitmap|clustered|nonclustered|global|local|temporary)\s+)*(?:index|table|view|materialized\s+view|sequence|trigger|function|procedure|package|type)\b|\b(?:alter|drop|truncate|rename)\s+(?:table|index|view|sequence|trigger|function|procedure|package|type)\b|\b(?:insert\s+into|delete\s+from|merge\s+into)\b|\bupdate\s+[\w.$"`]+\s+set\b|\bselect\b[\s\S]{0,4096}?\bfrom\b|```\s*sql\b|\bsql\s*:)/i.test(normalized)) {
    return "";
  }
  return normalized
    .trim();
}

function renderBlock(block: AdviceBlock, advice: ReturnType<typeof normalizeAdvice>) {
  switch (block.type) {
    case "diagnoses":
      return (
        <section key={block.id} className="advice-block advice-diagnoses">
          <h3><AlertTriangle size={16} />关键问题</h3>
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
    case "next":
      return (
        <section key={block.id} className={advice.outcome === "NEEDS_INPUT" ? "advice-block advice-next needs-input" : "advice-block advice-next"}>
          <h3>{advice.outcome === "NEEDS_INPUT" ? "还缺什么" : "建议补充"}</h3>
          {advice.nextStep && <p>{advice.nextStep}</p>}
          {advice.warning && <small>注意：{advice.warning}</small>}
        </section>
      );
  }
}

function buildBlocks(advice: ReturnType<typeof normalizeAdvice>): AdviceBlock[] {
  const blocks: AdviceBlock[] = [];
  if (advice.diagnoses.length > 0) {
    blocks.push({ id: "diagnoses", type: "diagnoses" });
  }
  appendRecommendations(blocks, advice);
  if (advice.outcome === "NEEDS_INPUT" || advice.warning) {
    blocks.push({ id: "next", type: "next" });
  }
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
      {!compact && <h3>{isIndex ? <Sparkles size={16} /> : <Wrench size={16} />}{title}</h3>}
      {isIndex && !compact && <strong className="advice-index-title">{indexTitle(index)}</strong>}
      {description && !compact && <p>{description}</p>}
      {sql && <SqlSnippet value={sql} />}
      {isIndex && index.writeCost && <small>写入成本：{index.writeCost}</small>}
      {risk && <small>注意：{risk}</small>}
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
  return {
    outcome,
    narrative: result?.analysisNarrative,
    summary: result?.summary || (task?.status === "FAILED" ? "任务失败，请检查输入或稍后重试。" : "本轮分析已完成。"),
    diagnoses,
    rewrite: rewrites[0],
    index: indexes[0],
    nextStep: outcome === "NEEDS_INPUT"
      ? missing[0] ? `请补充：${missing[0]}` : "请补充可验证的执行计划或表结构信息。"
      : "",
    warning: (result?.safetyWarnings || result?.riskWarnings || [])[0]
  };
}

function stripConclusionPrefix(value: string) {
  return value.replace(/^(?:最终结论|结论)\s*[：:]\s*/, "");
}

function firstText(primary?: string, legacy?: string[]) {
  return primary || legacy?.[0] || "";
}

function indexTitle(index: IndexCandidate) {
  const table = index.tableName || index.table || "目标表";
  const columns = index.columnOrder || index.columns || [];
  return columns.length > 0 ? `${table} (${columns.join(", ")})` : table;
}
