import { Copy, DatabaseZap, FileSearch, MessageSquareText } from "lucide-react";
import { useState } from "react";
import type { SqlTuningTask } from "../types/api";

interface ResultTabsProps {
  task?: SqlTuningTask;
}

const tabs = ["问题定位", "索引建议", "改写 SQL", "输入与来源"] as const;

export function ResultTabs({ task }: ResultTabsProps) {
  const [active, setActive] = useState<(typeof tabs)[number]>("问题定位");

  if (!task?.result) {
    return (
      <div className="result-empty">
        <DatabaseZap size={28} />
        <strong>等待调优结果</strong>
        <span>提交 SQL 后，结果会按问题、索引、改写和输入来源分区展示。</span>
      </div>
    );
  }

  return (
    <section className="result-panel">
      <div className="tabs">
        {tabs.map((tab) => (
          <button key={tab} className={active === tab ? "tab active" : "tab"} onClick={() => setActive(tab)}>
            {tab}
          </button>
        ))}
      </div>
      {active === "问题定位" && (
        <div className="card-list">
          {task.result.findings.map((finding, index) => (
            <article className="finding-card" key={`${finding.title}-${index}`}>
              <span>{finding.confidence || "model"}</span>
              <h3>{finding.title || finding.evidence || "模型发现"}</h3>
              {finding.evidence && <p>{finding.evidence}</p>}
              {finding.impact && <small>{finding.impact}</small>}
            </article>
          ))}
        </div>
      )}
      {active === "索引建议" && (
        <div className="card-list">
          {task.result.indexSuggestions.map((suggestion, index) => (
            <article className="index-card" key={`${suggestion.indexName}-${index}`}>
              <h3>{suggestion.indexName || "候选索引"}</h3>
              <div className="chip-row">{suggestion.columns.map((column) => <span key={column}>{column}</span>)}</div>
              {suggestion.benefit && <p>{suggestion.benefit}</p>}
              {suggestion.risk && <small>风险：{suggestion.risk}</small>}
              {suggestion.validation && <small>验证：{suggestion.validation}</small>}
            </article>
          ))}
        </div>
      )}
      {active === "改写 SQL" && (
        <CodeBlock value={task.result.rewriteSql || "-- 当前结果未提供改写 SQL"} />
      )}
      {active === "输入与来源" && <InputSourcePanel task={task} />}
    </section>
  );
}

function InputSourcePanel({ task }: { task: SqlTuningTask }) {
  const rows = [
    {
      title: task.inputType === "natural_language" ? "自然语言问题" : "主输入",
      value: task.originalSql,
      icon: <MessageSquareText size={16} />
    },
    { title: "表结构", value: task.schemaText, icon: <DatabaseZap size={16} /> },
    { title: "索引信息", value: task.indexText, icon: <FileSearch size={16} /> },
    { title: "EXPLAIN", value: task.explainText, icon: <FileSearch size={16} /> },
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
      <button onClick={copy} title="复制 SQL">
        <Copy size={16} />
      </button>
      <pre>{value}</pre>
    </div>
  );
}
