import { Copy, FileSearch, ListChecks, ShieldAlert, Sparkles, Wrench } from "lucide-react";
import type { AnalysisNarrativeSection, IndexCandidate, RewriteCandidate, ValidationStep } from "../types/api";

export interface AdviceBasisItem {
  title?: string;
  detail?: string;
}

type ReadableBlock = {
  type: "paragraph" | "unordered" | "ordered";
  lines: string[];
};

export function AdviceBasisBlock({ items }: { items: AdviceBasisItem[] }) {
  return (
    <section className="advice-block advice-basis">
      <h3><FileSearch size={16} />问题在哪</h3>
      <ol>
        {items.map((item, index) => (
          <li key={`${item.title || item.detail}-${index}`}>
            {item.title && <strong>{item.title}</strong>}
            {item.detail && <span>{item.detail}</span>}
          </li>
        ))}
      </ol>
    </section>
  );
}

export function RecommendationBlock({ type, candidate }: { type: "rewrite" | "index"; candidate: RewriteCandidate | IndexCandidate }) {
  const isIndex = type === "index";
  const index = candidate as IndexCandidate;
  const rewrite = candidate as RewriteCandidate;
  const sql = isIndex ? index.ddl : (rewrite.sql || rewrite.rewrittenSql);
  const title = isIndex
    ? (index.ddl?.trim() ? "建议验证的索引方案" : "建议验证的索引方向")
    : "建议验证的 SQL 改写";
  const description = stripEvidenceIds(isIndex ? index.benefit || "" : firstText(rewrite.change, rewrite.changes));
  const risk = stripEvidenceIds(isIndex ? index.risk || "" : firstText(rewrite.risk, rewrite.risks));
  const writeCost = stripEvidenceIds(index.writeCost || "");
  const className = isIndex
    ? "advice-block advice-recommendation advice-index"
    : "advice-block advice-recommendation advice-rewrite";

  return (
    <section className={className}>
      <h3>{isIndex ? <Sparkles size={16} /> : <Wrench size={16} />}{title}</h3>
      {isIndex && <strong className="advice-index-title">{indexTitle(index)}</strong>}
      {description && <p><InlineText value={description} /></p>}
      {sql && <SqlSnippet value={sql} />}
      {isIndex && writeCost && <small>写入成本：{writeCost}</small>}
      {risk && <small>前提：{risk}</small>}
    </section>
  );
}

export function ValidationPlanView({ plan }: { plan: Array<ValidationStep | string> }) {
  const entries = plan.map((item) => typeof item === "string"
    ? stripEvidenceIds(item)
    : stripEvidenceIds([item.action, item.expectedSignal].filter(Boolean).join("："))
  ).filter(Boolean).slice(0, 4);

  if (entries.length === 0) {
    return null;
  }
  return (
    <section className="advice-block advice-validation-plan">
      <h3><ListChecks size={16} />怎么确认有效</h3>
      <ul className="advice-list">
        {entries.map((entry, index) => <li key={`${entry}-${index}`}><InlineText value={entry} /></li>)}
      </ul>
    </section>
  );
}

export function NarrativeSectionView({ section }: { section: AnalysisNarrativeSection }) {
  const kind = section.kind?.toUpperCase() || "";
  const maxItems = kind === "EVIDENCE" && hasScreenshotEvidenceNote(section.body) ? 4 : kind === "EVIDENCE" ? 3 : 4;
  return (
    <section className={`advice-block advice-narrative advice-narrative-${kind.toLowerCase()}`}>
      <h3>{narrativeIcon(kind)}{narrativeHeading(kind, section.title)}</h3>
      <NarrativeBody value={section.body} maxItems={maxItems} />
    </section>
  );
}

export function MissingAdviceBlock({ message }: { message: string }) {
  return (
    <section className="advice-block advice-next needs-input">
      <h3><FileSearch size={16} />还需要什么</h3>
      <p>{message}</p>
    </section>
  );
}

export function WarningAdviceBlock({ message }: { message: string }) {
  return (
    <section className="advice-block advice-narrative advice-narrative-caution">
      <h3><ShieldAlert size={16} />暂时不要做</h3>
      <NarrativeBody value={message} maxItems={3} />
    </section>
  );
}

function NarrativeBody({ value, maxItems }: { value: string; maxItems: number }) {
  return (
    <div className="advice-copy">
      {splitReadableBlocks(stripEvidenceIds(value), maxItems).map((block, index) => block.type === "unordered" ? (
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

function splitReadableBlocks(value: string, maxItems: number) {
  const blocks: ReadableBlock[] = [];
  let current: ReadableBlock | undefined;
  let itemCount = 0;
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
    if (itemCount >= maxItems) {
      return;
    }
    const ordered = line.match(/^\d+[.)、]\s*(.+)$/);
    const unordered = line.match(/^(?:[-*•])\s*(.+)$/);
    const type: ReadableBlock["type"] = ordered ? "ordered" : unordered ? "unordered" : "paragraph";
    const text = ordered?.[1] || unordered?.[1] || line;
    if (!current || current.type !== type) {
      flush();
      current = { type, lines: [] };
    }
    current.lines.push(text);
    itemCount += 1;
  });
  flush();
  return blocks;
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

function hasScreenshotEvidenceNote(value: string) {
  return /来自截图/.test(value) && /文本\s*EXPLAIN/i.test(value);
}

function narrativeHeading(kind: string, fallback: string) {
  switch (kind) {
    case "EVIDENCE":
      return "问题在哪";
    case "ACTION":
      return "现在怎么做";
    case "VALIDATION":
      return "怎么确认有效";
    case "CAUTION":
      return "暂时不要做";
    default:
      return stripEvidenceIds(fallback);
  }
}

function narrativeIcon(kind: string) {
  switch (kind) {
    case "EVIDENCE":
      return <FileSearch size={16} />;
    case "ACTION":
      return <Wrench size={16} />;
    case "VALIDATION":
      return <ListChecks size={16} />;
    case "CAUTION":
      return <ShieldAlert size={16} />;
    default:
      return <Sparkles size={16} />;
  }
}

function firstText(primary?: string, legacy?: string[]) {
  return primary || legacy?.[0] || "";
}

function indexTitle(index: IndexCandidate) {
  const table = stripEvidenceIds(index.tableName || index.table || "目标表") || "目标表";
  const columns = (index.columnOrder || index.columns || []).map(stripEvidenceIds).filter(Boolean);
  return columns.length > 0 ? `${table} (${columns.join(", ")})` : table;
}

export function stripEvidenceIds(value: string) {
  return value
    .replace(/[\[（(【]\s*E_[A-Z0-9_]+\s*[\]）)】]/gi, "")
    .replace(/\bE_[A-Z0-9_]+\b/gi, "")
    .replace(/[ \t]+([，。；：,.!?])/g, "$1")
    .replace(/[ \t]{2,}/g, " ")
    .trim();
}
