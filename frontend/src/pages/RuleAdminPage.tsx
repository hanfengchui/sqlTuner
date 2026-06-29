import { ShieldCheck } from "lucide-react";
import { useEffect, useState } from "react";
import { api } from "../api/client";
import type { RuleFinding } from "../types/api";

export function RuleAdminPage() {
  const [rules, setRules] = useState<RuleFinding[]>([]);

  useEffect(() => {
    api.rules().then(setRules);
  }, []);

  return (
    <div className="admin-page rule-admin-page">
      <header>
        <span>Rules</span>
        <h1>确定性规则</h1>
        <p>规则结果会作为事实输入模型，模型只能解释和补充，不能覆盖规则事实。</p>
      </header>
      <section className="rule-overview">
        <article>
          <strong>{rules.length}</strong>
          <span>规则总数</span>
        </article>
        <article>
          <strong>{countBySeverity(rules, "HIGH")}</strong>
          <span>高风险规则</span>
        </article>
        <article>
          <strong>{countBySeverity(rules, "WARN")}</strong>
          <span>预警规则</span>
        </article>
      </section>
      <div className="rule-grid">
        {rules.map((rule) => (
          <article key={rule.code} className={`rule-card severity-${rule.severity.toLowerCase()}`}>
            <ShieldCheck size={18} />
            <span>{severityLabel(rule.severity)}</span>
            <h3>{rule.title}</h3>
            <p>{rule.evidence}</p>
            <small>{rule.suggestion}</small>
            <code>{rule.code}</code>
          </article>
        ))}
      </div>
    </div>
  );
}

function countBySeverity(rules: RuleFinding[], severity: RuleFinding["severity"]) {
  return rules.filter((rule) => rule.severity === severity).length;
}

function severityLabel(severity: RuleFinding["severity"]) {
  if (severity === "HIGH") {
    return "高风险";
  }
  if (severity === "WARN") {
    return "预警";
  }
  return "提示";
}
