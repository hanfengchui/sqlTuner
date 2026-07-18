import { ClipboardList, FileSearch, ListChecks, ShieldAlert } from "lucide-react";
import type { ReactNode } from "react";
import type { AnalysisNarrativeSection, Diagnosis, IndexCandidate, RewriteCandidate, SqlTuningTask, ValidationStep } from "../types/api";

export function TuningEvidenceDetails({ task }: { task?: SqlTuningTask }) {
  const result = task?.result;
  if (!task || !result) {
    return null;
  }

  const diagnoses = result.diagnoses || (result.findings || []).map((finding) => ({
    title: finding.title,
    impact: finding.impact || finding.evidence,
    confidence: finding.confidence,
    severity: "INFO"
  }));
  const rewrites = result.rewriteCandidates || (result.rewriteSql ? [{ sql: result.rewriteSql }] : []);
  const indexes: IndexCandidate[] = result.indexCandidates || (result.indexSuggestions || []).map((suggestion) => ({
    tableName: suggestion.indexName,
    columnOrder: suggestion.columns,
    benefit: suggestion.benefit,
    risk: suggestion.risk,
    validation: suggestion.validation
  }));
  const validationPlan = result.validationPlan || result.validationSteps || [];
  const missingInformation = result.missingInformation || result.needMoreInfo || [];
  const safetyWarnings = result.safetyWarnings || result.riskWarnings || [];
  const assessment = result.contextAssessment || task.contextAssessment;

  return (
    <section className="evidence-details" aria-label="完整任务依据">
      <header>
        <div>
          <span>完整任务依据</span>
          <h2>审计详情</h2>
        </div>
        <ClipboardList size={20} />
      </header>

      {result.analysisNarrative && (
        <DetailSection title="工程师结论" open>
          <p className="audit-narrative-conclusion">{result.analysisNarrative.conclusion}</p>
          <div className="evidence-record-list">
            {result.analysisNarrative.sections.map((section, index) => (
              <NarrativeDetail key={`${section.kind}-${section.title}-${index}`} section={section} />
            ))}
          </div>
        </DetailSection>
      )}

      <DetailSection title="上下文门禁" open>
        <dl className="evidence-facts">
          <div><dt>完整度</dt><dd>{assessment?.completeness || "未分级"}</dd></div>
          <div><dt>最高置信度</dt><dd>{assessment?.maxConfidence || assessment?.confidenceCeiling || "LOW"}</dd></div>
        </dl>
        <DetailList title="可用证据" items={assessment?.availableEvidence} empty="未提供" />
        <DetailList title="缺失信息" items={assessment?.missingInformation || missingInformation} empty="无" />
        <DetailList title="门禁说明" items={assessment?.policyNotes} empty="无" />
      </DetailSection>

      <DetailSection title={`证据目录 (${result.evidenceCatalog?.length || 0})`}>
        {result.evidenceCatalog?.length ? (
          <div className="evidence-record-list">
            {result.evidenceCatalog.map((item) => (
              <article key={item.id}>
                <strong>{item.id} · {item.source}</strong>
                <p>{item.summary}</p>
                <small>可信等级：{item.trustLevel || item.reliability || "未分级"}</small>
              </article>
            ))}
          </div>
        ) : <EmptyDetail text="没有可用的证据目录。" />}
      </DetailSection>

      <DetailSection title={`诊断 (${diagnoses.length})`}>
        <div className="evidence-record-list">
          {diagnoses.map((diagnosis, index) => <DiagnosisDetail key={`${diagnosis.title}-${index}`} diagnosis={diagnosis} />)}
          {diagnoses.length === 0 && <EmptyDetail text="没有可确认诊断。" />}
        </div>
      </DetailSection>

      <DetailSection title={`改写候选 (${rewrites.length})`}>
        <div className="evidence-record-list">
          {rewrites.map((rewrite, index) => <RewriteDetail key={`${rewrite.sql || rewrite.rewrittenSql}-${index}`} rewrite={rewrite} />)}
          {rewrites.length === 0 && <EmptyDetail text="没有可验证的改写候选。" />}
        </div>
      </DetailSection>

      <DetailSection title={`索引候选 (${indexes.length})`}>
        <div className="evidence-record-list">
          {indexes.map((index, position) => <IndexDetail key={`${index.tableName || index.table}-${position}`} index={index} />)}
          {indexes.length === 0 && <EmptyDetail text="没有可验证的索引候选。" />}
        </div>
      </DetailSection>

      <DetailSection title="验证与风险">
        <DetailList title="验证计划" items={validationPlan.map(formatValidation)} empty="无" icon={<ListChecks size={15} />} />
        <DetailList title="缺失信息" items={missingInformation} empty="无" />
        <DetailList title="安全警告" items={safetyWarnings} empty="无" icon={<ShieldAlert size={15} />} />
        {result.review && (
          <article className="evidence-review">
            <strong>深度复核：{result.review.verdict || "NOT_REQUESTED"}</strong>
            {result.review.notes && <p>{result.review.notes}</p>}
            {result.review.revisions?.length ? <DetailList title="修订" items={result.review.revisions} /> : null}
          </article>
        )}
      </DetailSection>

      <DetailSection title={`规则与链路 (${task.ruleFindings.length} / ${task.artifacts.length})`}>
        <DetailList title="确定性规则命中" items={task.ruleFindings.map((finding) => `${finding.code}：${finding.title}；${finding.suggestion}`)} empty="无" />
        <div className="artifact-list">
          {task.artifacts.map((artifact, index) => (
            <article key={`${artifact.nodeName}-${artifact.createdAt}-${index}`}>
              <strong>{artifact.nodeName}</strong>
              <p>{artifact.summary}</p>
              <small>{artifact.createdAt}</small>
            </article>
          ))}
          {task.artifacts.length === 0 && <EmptyDetail text="暂无链路记录。" />}
        </div>
      </DetailSection>
    </section>
  );
}

function DetailSection({ title, open = false, children }: { title: string; open?: boolean; children: ReactNode }) {
  return (
    <details className="evidence-section" open={open}>
      <summary>{title}</summary>
      <div>{children}</div>
    </details>
  );
}

function DiagnosisDetail({ diagnosis }: { diagnosis: Diagnosis }) {
  return (
    <article>
      <strong>{diagnosis.severity || "INFO"} · {diagnosis.confidence || "LOW"} · {diagnosis.title || "模型诊断"}</strong>
      {diagnosis.impact && <p>{diagnosis.impact}</p>}
      {diagnosis.precondition && <small>前置条件：{diagnosis.precondition}</small>}
      {diagnosis.preconditions?.length ? <small>前置条件：{diagnosis.preconditions.join("；")}</small> : null}
      <EvidenceRefs refs={diagnosis.evidenceRefs} />
    </article>
  );
}

function NarrativeDetail({ section }: { section: AnalysisNarrativeSection }) {
  return (
    <article>
      <strong>{section.title}</strong>
      <p>{section.body}</p>
      <EvidenceRefs refs={section.evidenceRefs} />
    </article>
  );
}

function RewriteDetail({ rewrite }: { rewrite: RewriteCandidate }) {
  return (
    <article>
      <strong>{rewrite.title || "改写候选"}</strong>
      <pre>{rewrite.sql || rewrite.rewrittenSql || "未提供 SQL"}</pre>
      <DetailList title="变化" items={strings(rewrite.change, rewrite.changes)} empty="未说明" />
      <DetailList title="语义检查" items={strings(rewrite.semanticCheck, rewrite.semanticChecks)} empty="未说明" />
      <DetailList title="风险" items={strings(rewrite.risk, rewrite.risks)} empty="未说明" />
      <DetailList title="验证" items={strings(rewrite.validation, rewrite.validationSteps)} empty="未说明" />
      <EvidenceRefs refs={rewrite.evidenceRefs} />
    </article>
  );
}

function IndexDetail({ index }: { index: IndexCandidate }) {
  const columns = index.columnOrder || index.columns || [];
  return (
    <article>
      <strong>{index.tableName || index.table || "目标表"}{index.confidence ? ` · ${index.confidence}` : ""}</strong>
      {columns.length > 0 && <p>列顺序：{columns.join(", ")}</p>}
      {index.ddl && <pre>{index.ddl}</pre>}
      {index.benefit && <p>收益：{index.benefit}</p>}
      {index.writeCost && <p>写入成本：{index.writeCost}</p>}
      <DetailList title="风险" items={strings(index.risk, index.risks)} empty="未说明" />
      <DetailList title="验证" items={strings(index.validation, index.validationMethod)} empty="未说明" />
      <EvidenceRefs refs={index.evidenceRefs} />
    </article>
  );
}

function DetailList({ title, items, empty, icon }: { title: string; items?: string[]; empty?: string; icon?: ReactNode }) {
  return (
    <div className="detail-list">
      <strong>{icon}{title}</strong>
      {items?.length ? <ul>{items.map((item, index) => <li key={`${item}-${index}`}>{item}</li>)}</ul> : <p>{empty || "无"}</p>}
    </div>
  );
}

function EvidenceRefs({ refs }: { refs?: string[] }) {
  return refs?.length ? <small className="detail-evidence-refs">证据：{refs.join(", ")}</small> : null;
}

function EmptyDetail({ text }: { text: string }) {
  return <p className="detail-empty">{text}</p>;
}

function strings(...values: Array<string | string[] | undefined>) {
  return values.flatMap((value) => !value ? [] : Array.isArray(value) ? value.filter(Boolean) : [value]);
}

function formatValidation(step: ValidationStep | string) {
  if (typeof step === "string") {
    return step;
  }
  return [step.action, step.expectedSignal, step.evidenceRefs?.length ? `证据：${step.evidenceRefs.join(", ")}` : ""].filter(Boolean).join("；");
}
