import * as Tabs from "@radix-ui/react-tabs";
import { Copy, DatabaseZap, FileSearch, MessageSquareText, ShieldAlert } from "lucide-react";
import type React from "react";
import { useMemo } from "react";
import type { Diagnosis, EvidenceItem, IndexCandidate, RewriteCandidate, SqlTuningTask, ValidationStep } from "../types/api";

interface ResultTabsProps {
  task?: SqlTuningTask;
}

export function ResultTabs({ task }: ResultTabsProps) {
  const normalized = useMemo(() => normalizeResult(task), [task]);

  if (!task?.result && task?.status !== "FAILED") {
    return (
      <div className="result-empty">
        <DatabaseZap size={28} />
        <strong>等待调优结果</strong>
        <span>结果会按证据、诊断、改写、索引、验证和风险分区展示。</span>
      </div>
    );
  }

  return (
    <section className="result-panel">
      <div className="result-summary">
        <div>
          <span>{normalized.outcome === "NEEDS_INPUT" ? "NEEDS_INPUT" : task?.status || "REPORT"}</span>
          <h2>{normalized.summary}</h2>
        </div>
        <div className={`outcome-badge ${normalized.outcome === "NEEDS_INPUT" ? "warn" : "ok"}`}>
          {normalized.outcome === "NEEDS_INPUT" ? "需要更多证据" : "可审阅建议"}
        </div>
      </div>

      <Tabs.Root defaultValue="evidence" className="tabs-root">
        <Tabs.List className="tabs" aria-label="调优报告分区">
          <Tabs.Trigger className="tab" value="evidence">证据</Tabs.Trigger>
          <Tabs.Trigger className="tab" value="diagnosis">诊断</Tabs.Trigger>
          <Tabs.Trigger className="tab" value="rewrite">改写</Tabs.Trigger>
          <Tabs.Trigger className="tab" value="index">索引</Tabs.Trigger>
          <Tabs.Trigger className="tab" value="validation">验证</Tabs.Trigger>
          <Tabs.Trigger className="tab" value="source">输入</Tabs.Trigger>
        </Tabs.List>

        <Tabs.Content value="evidence" className="tab-content">
          <ContextAssessment task={task} />
          <div className="card-list">
            {normalized.evidence.map((item) => (
              <article className="evidence-card" key={item.id}>
                <span>{item.id} · {item.source}</span>
                <p>{item.summary}</p>
                {(item.trustLevel || item.reliability) && <small>可信等级：{item.trustLevel || item.reliability}</small>}
              </article>
            ))}
            {normalized.evidence.length === 0 && <EmptyBlock text="暂无证据目录；请补充 schema、索引、EXPLAIN 或运行指标。" />}
          </div>
        </Tabs.Content>

        <Tabs.Content value="diagnosis" className="tab-content">
          <div className="card-list">
            {normalized.diagnoses.map((diagnosis, index) => (
              <article className={`finding-card severity-${(diagnosis.severity || "info").toLowerCase()}`} key={`${diagnosis.title}-${index}`}>
                <span>{diagnosis.severity || "INFO"} · {diagnosis.confidence || "LOW"}</span>
                <h3>{diagnosis.title || "模型诊断"}</h3>
                {diagnosis.impact && <p>{diagnosis.impact}</p>}
                <EvidenceRefs refs={diagnosis.evidenceRefs} />
                {diagnosis.precondition && <small>前置条件：{diagnosis.precondition}</small>}
                {!diagnosis.precondition && diagnosis.preconditions?.length ? <small>前置条件：{diagnosis.preconditions.join("；")}</small> : null}
              </article>
            ))}
            {normalized.diagnoses.length === 0 && <EmptyBlock text="当前结果没有可确认诊断。" />}
          </div>
        </Tabs.Content>

        <Tabs.Content value="rewrite" className="tab-content">
          <div className="card-list">
            {normalized.rewrites.map((rewrite, index) => (
              <article className="rewrite-card" key={`${rewrite.title}-${index}`}>
                <h3>{rewrite.title || `改写候选 ${index + 1}`}</h3>
                <CodeBlock value={rewrite.rewrittenSql || rewrite.sql || "-- 未提供改写 SQL"} />
                <List title="变化" items={stringItems(rewrite.change, rewrite.changes)} empty="未说明变化" />
                <List title="语义检查" items={stringItems(rewrite.semanticCheck, rewrite.semanticChecks)} empty="未提供语义检查" />
                <List title="风险" items={stringItems(rewrite.risk, rewrite.risks)} empty="未提供风险说明" />
                <List title="验证" items={stringItems(rewrite.validation, rewrite.validationSteps)} empty="未提供验证步骤" />
                <EvidenceRefs refs={rewrite.evidenceRefs} />
              </article>
            ))}
            {normalized.rewrites.length === 0 && <EmptyBlock text="没有足够证据生成改写候选。" />}
          </div>
        </Tabs.Content>

        <Tabs.Content value="index" className="tab-content">
          <div className="card-list">
            {normalized.indexes.map((suggestion, index) => (
              <article className="index-card" key={`${suggestion.tableName || suggestion.table}-${index}`}>
                <span>
                  {suggestion.tableName || suggestion.table || "未知表"}
                  {suggestion.confidence ? ` · ${suggestion.confidence}` : ""}
                </span>
                <h3>{suggestion.ddl || "索引方向"}</h3>
                <div className="chip-row">{(suggestion.columnOrder || suggestion.columns || []).map((column) => <span key={column}>{column}</span>)}</div>
                {suggestion.benefit && <p>{suggestion.benefit}</p>}
                {suggestion.writeCost && <small>写入成本：{suggestion.writeCost}</small>}
                <List title="风险" items={stringItems(suggestion.risk, suggestion.risks)} empty="未提供风险说明" />
                <List title="验证" items={stringItems(suggestion.validation, suggestion.validationMethod)} empty="未提供验证方法" />
                <EvidenceRefs refs={suggestion.evidenceRefs} />
              </article>
            ))}
            {normalized.indexes.length === 0 && <EmptyBlock text="缺少当前索引、统计或计划时不会生成确定性索引 DDL。" />}
          </div>
        </Tabs.Content>

        <Tabs.Content value="validation" className="tab-content">
          <div className="validation-grid">
            <List title="验证计划" items={normalized.validationPlan} />
            <List title="缺失信息" items={normalized.missingInformation} empty="暂无缺失信息" />
            <List title="安全警告" items={normalized.safetyWarnings} empty="暂无安全警告" icon={<ShieldAlert size={16} />} />
            {normalized.review && (
              <article className="review-card">
                <span>Deep Review</span>
                <h3>{normalized.review.verdict || "未复核"}</h3>
                {normalized.review.notes && <p>{normalized.review.notes}</p>}
                {normalized.review.revisions?.length ? <List title="修订" items={normalized.review.revisions} /> : null}
              </article>
            )}
          </div>
        </Tabs.Content>

        <Tabs.Content value="source" className="tab-content">
          <InputSourcePanel task={task} />
        </Tabs.Content>
      </Tabs.Root>
    </section>
  );
}

function ContextAssessment({ task }: { task?: SqlTuningTask }) {
  const assessment = task?.result?.contextAssessment || task?.contextAssessment;
  if (!assessment) {
    return null;
  }
  return (
    <article className="assessment-card">
      <span>Context Assessment</span>
      <h3>{assessment.completeness || "未分级"} · {assessment.maxConfidence || assessment.confidenceCeiling || "LOW"}</h3>
      {assessment.summary && <p>{assessment.summary}</p>}
      {assessment.availableEvidence?.length ? <List title="可用证据" items={assessment.availableEvidence} /> : null}
      {assessment.missingInformation?.length ? <List title="缺失信息" items={assessment.missingInformation} /> : null}
      {assessment.policyNotes?.length ? <List title="策略约束" items={assessment.policyNotes} /> : null}
    </article>
  );
}

function InputSourcePanel({ task }: { task: SqlTuningTask }) {
  const rows = [
    { title: task.inputType === "natural_language" ? "自然语言问题" : "主输入", value: task.originalSql, icon: <MessageSquareText size={16} /> },
    { title: "表结构", value: task.schemaText, icon: <DatabaseZap size={16} /> },
    { title: "索引信息", value: task.indexText, icon: <FileSearch size={16} /> },
    { title: "EXPLAIN", value: task.explainText, icon: <FileSearch size={16} /> },
    { title: "OB 版本", value: task.obVersion, icon: <FileSearch size={16} /> },
    { title: "表统计", value: task.tableStatsText, icon: <FileSearch size={16} /> },
    { title: "运行指标", value: task.runtimeMetricsText, icon: <FileSearch size={16} /> },
    { title: "业务语义约束", value: task.businessInvariants, icon: <MessageSquareText size={16} /> },
    { title: "业务说明", value: task.businessContext, icon: <MessageSquareText size={16} /> }
  ];

  return (
    <div className="source-list">
      {rows.map((row) => (
        <article key={row.title} className={row.value?.trim() ? "" : "muted"}>
          <div>
            {row.icon}
            <strong>{row.title}</strong>
          </div>
          <pre>{row.value?.trim() || "未提供"}</pre>
        </article>
      ))}
    </div>
  );
}

function CodeBlock({ value }: { value: string }) {
  async function copy() {
    await navigator.clipboard.writeText(value);
  }
  return (
    <div className="code-block">
      <button onClick={copy} title="复制 SQL" aria-label="复制 SQL">
        <Copy size={16} />
      </button>
      <pre>{value}</pre>
    </div>
  );
}

function EvidenceRefs({ refs }: { refs?: string[] }) {
  if (!refs?.length) {
    return null;
  }
  return <div className="evidence-refs">{refs.map((ref) => <span key={ref}>{ref}</span>)}</div>;
}

function List({ title, items, empty, icon }: { title: string; items?: string[]; empty?: string; icon?: React.ReactNode }) {
  return (
    <article className="list-card">
      <h3>{icon}{title}</h3>
      {items?.length ? (
        <ul>
          {items.map((item, index) => <li key={`${item}-${index}`}>{item}</li>)}
        </ul>
      ) : (
        <p>{empty || "暂无"}</p>
      )}
    </article>
  );
}

function EmptyBlock({ text }: { text: string }) {
  return <div className="empty-block">{text}</div>;
}

function normalizeResult(task?: SqlTuningTask) {
  const result = task?.result;
  const evidence: EvidenceItem[] = result?.evidenceCatalog || [];
  const diagnoses: Diagnosis[] =
    result?.diagnoses ||
    result?.findings?.map((finding) => ({
      title: finding.title,
      severity: "INFO",
      impact: finding.impact || finding.evidence,
      confidence: finding.confidence,
      evidenceRefs: []
    })) ||
    [];
  const rewrites: RewriteCandidate[] =
    result?.rewriteCandidates ||
    (result?.rewriteSql ? [{ title: "Legacy rewrite", rewrittenSql: result.rewriteSql, changes: ["由旧字段 rewriteSql 映射"] }] : []);
  const indexes: IndexCandidate[] =
    result?.indexCandidates ||
    result?.indexSuggestions?.map((suggestion) => ({
      tableName: suggestion.indexName,
      columns: suggestion.columns,
      benefit: suggestion.benefit,
      risk: suggestion.risk,
      validation: suggestion.validation
    })) ||
    [];

  return {
    outcome: result?.outcome || task?.outcome || "ADVICE",
    summary: result?.summary || (task?.status === "FAILED" ? "任务失败，请检查输入或稍后重试。" : "本轮结果正在生成。"),
    evidence,
    diagnoses,
    rewrites,
    indexes,
    validationPlan: formatValidationPlan(result?.validationPlan, result?.validationSteps),
    missingInformation: result?.missingInformation || result?.needMoreInfo || [],
    safetyWarnings: result?.safetyWarnings || result?.riskWarnings || [],
    review: result?.review
  };
}

function stringItems(...values: Array<string | string[] | undefined>) {
  return values.flatMap((value) => {
    if (!value) {
      return [];
    }
    if (Array.isArray(value)) {
      return value.filter(Boolean);
    }
    return [value];
  });
}

function formatValidationPlan(plan?: Array<ValidationStep | string>, legacySteps?: string[]) {
  if (!plan?.length) {
    return legacySteps || [];
  }
  return plan.map((step) => {
    if (typeof step === "string") {
      return step;
    }
    const parts = [step.action || "验证步骤"];
    if (step.expectedSignal) {
      parts.push(`观察: ${step.expectedSignal}`);
    }
    if (step.evidenceRefs?.length) {
      parts.push(`证据: ${step.evidenceRefs.join(", ")}`);
    }
    return parts.join("；");
  });
}
