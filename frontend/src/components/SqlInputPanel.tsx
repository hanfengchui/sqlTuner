import { Database, FileText, Layers, Send, Sparkles } from "lucide-react";
import type { KeyboardEvent } from "react";
import { useState } from "react";
import type { SqlDialect } from "../types/api";

export interface SqlInputValue {
  dbDialect: SqlDialect;
  sqlText: string;
  inputType: "sql" | "natural_language";
  schemaText: string;
  indexText: string;
  explainText: string;
  businessContext: string;
  deepAnalysis: boolean;
}

interface SqlInputPanelProps {
  loading: boolean;
  onSubmit: (value: SqlInputValue) => void;
  compact?: boolean;
}

export function SqlInputPanel({ loading, onSubmit, compact = false }: SqlInputPanelProps) {
  const [value, setValue] = useState<SqlInputValue>({
    dbDialect: "OceanBase MySQL",
    sqlText: "",
    inputType: "sql",
    schemaText: "",
    indexText: "",
    explainText: "",
    businessContext: "",
    deepAnalysis: true
  });
  const [contextOpen, setContextOpen] = useState(false);
  const [isComposing, setIsComposing] = useState(false);

  function update<K extends keyof SqlInputValue>(key: K, next: SqlInputValue[K]) {
    setValue((current) => ({ ...current, [key]: next }));
  }

  function submit() {
    if (!value.sqlText.trim() || loading) {
      return;
    }
    onSubmit({ ...value, inputType: detectInputType(value.sqlText) });
    setValue((current) => ({
      ...current,
      sqlText: "",
      schemaText: "",
      indexText: "",
      explainText: "",
      businessContext: ""
    }));
    setContextOpen(false);
  }

  function handleMainKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (isComposing || event.nativeEvent.isComposing || event.keyCode === 229) {
      return;
    }
    if (event.key === "Enter" && !event.shiftKey && !event.metaKey && !event.ctrlKey) {
      event.preventDefault();
      submit();
    }
  }

  function detectInputType(input: string): SqlInputValue["inputType"] {
    const normalized = input.trim().toLowerCase();
    if (/^(select|update|delete|insert|with|explain)\b/.test(normalized)) {
      return "sql";
    }
    return "natural_language";
  }

  const detectedType = detectInputType(value.sqlText);

  return (
    <section className={compact ? "sql-input-panel compact" : "sql-input-panel"}>
      <div className="sql-panel-head">
        <div>
          <span>Prompt</span>
          <strong>写下 SQL 或补充信息</strong>
        </div>
        <div className="sql-panel-controls">
          <select value={value.dbDialect} onChange={(event) => update("dbDialect", event.target.value as SqlDialect)} aria-label="数据库方言">
            <option value="OceanBase MySQL">OB MySQL</option>
            <option value="OceanBase Oracle">OB Oracle</option>
          </select>
          <em>{detectedType === "sql" ? "SQL 模式" : "自然语言"}</em>
        </div>
      </div>
      <textarea
        value={value.sqlText}
        onChange={(event) => update("sqlText", event.target.value)}
        onCompositionStart={() => setIsComposing(true)}
        onCompositionEnd={() => setIsComposing(false)}
        onKeyDown={handleMainKeyDown}
        placeholder="粘贴慢 SQL，或直接描述：这个订单查询很慢，帮我判断需要哪些信息..."
      />
      <div className="input-toolbar">
        <button className={value.deepAnalysis ? "toggle active" : "toggle"} onClick={() => update("deepAnalysis", !value.deepAnalysis)}>
          <Sparkles size={15} />
          深度分析
        </button>
        <button className="ghost-button" onClick={() => setContextOpen((open) => !open)}>
          <Layers size={15} />
          上下文 {contextOpen ? "收起" : "补充"}
        </button>
        <span className="input-meter">{value.sqlText.trim().length} chars</span>
        <button className="send-button" disabled={loading || !value.sqlText.trim()} onClick={submit} title="发送">
          <Send size={18} />
        </button>
      </div>
      {contextOpen && (
        <div className="context-grid">
          <label>
            <span>
              <Database size={14} />
              表结构
            </span>
            <textarea value={value.schemaText} onChange={(event) => update("schemaText", event.target.value)} placeholder="CREATE TABLE / 字段说明" />
          </label>
          <label>
            <span>
              <FileText size={14} />
              索引信息
            </span>
            <textarea value={value.indexText} onChange={(event) => update("indexText", event.target.value)} placeholder="SHOW INDEX / 索引清单" />
          </label>
          <label>
            <span>
              <FileText size={14} />
              EXPLAIN
            </span>
            <textarea value={value.explainText} onChange={(event) => update("explainText", event.target.value)} placeholder="粘贴 EXPLAIN 输出" />
          </label>
          <label>
            <span>
              <FileText size={14} />
              业务说明
            </span>
            <textarea value={value.businessContext} onChange={(event) => update("businessContext", event.target.value)} placeholder="慢在哪里、期望耗时、数据量" />
          </label>
        </div>
      )}
      <p className="input-hint">Enter 发送 · Shift + Enter 换行 · 支持 SQL 和自然语言 · 本次输入来源会进入报告</p>
    </section>
  );
}
