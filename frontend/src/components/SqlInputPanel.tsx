import CodeMirror from "@uiw/react-codemirror";
import { sql } from "@codemirror/lang-sql";
import { oneDark } from "@codemirror/theme-one-dark";
import { EditorView } from "@codemirror/view";
import {
  Activity,
  Database,
  FileText,
  Gauge,
  ImagePlus,
  Layers,
  ListChecks,
  Send,
  ShieldCheck,
  Sparkles,
  Trash2,
  Upload
} from "lucide-react";
import type React from "react";
import { useMemo, useRef, useState } from "react";
import type { PlanImage, SqlDialect } from "../types/api";

const MAX_IMAGE_COUNT = 3;
const MAX_IMAGE_BYTES = 2 * 1024 * 1024;
const MAX_TOTAL_IMAGE_BYTES = 5 * 1024 * 1024;
const MAX_IMAGE_EDGE = 2048;
const SUPPORTED_IMAGE_TYPES = new Set(["image/png", "image/jpeg", "image/webp"]);

interface PreparedPlanImage extends PlanImage {
  size: number;
}

export interface SqlInputValue {
  dbDialect: SqlDialect;
  sqlText: string;
  inputType: "sql" | "report_text" | "natural_language";
  schemaText: string;
  indexText: string;
  explainText: string;
  businessContext: string;
  obVersion: string;
  tableStatsText: string;
  runtimeMetricsText: string;
  businessInvariants: string;
  allowedActions: string[];
  planImages: PlanImage[];
  deepAnalysis: boolean;
}

type SqlInputDraft = Omit<SqlInputValue, "planImages"> & { planImages: PreparedPlanImage[] };

interface SqlInputPanelProps {
  loading: boolean;
  onSubmit: (value: SqlInputValue) => void;
  compact?: boolean;
}

const actionOptions = [
  { value: "diagnosis", label: "诊断" },
  { value: "rewrite", label: "改写候选" },
  { value: "index", label: "索引方向" },
  { value: "validation", label: "验证计划" }
];

export function SqlInputPanel({ loading, onSubmit, compact = false }: SqlInputPanelProps) {
  const [value, setValue] = useState<SqlInputDraft>({
    dbDialect: "OceanBase MySQL",
    sqlText: "",
    inputType: "sql",
    schemaText: "",
    indexText: "",
    explainText: "",
    businessContext: "",
    obVersion: "",
    tableStatsText: "",
    runtimeMetricsText: "",
    businessInvariants: "",
    allowedActions: ["diagnosis", "rewrite", "index", "validation"],
    planImages: [],
    deepAnalysis: false
  });
  const [contextOpen, setContextOpen] = useState(false);
  const [imageError, setImageError] = useState("");
  const [draggingImages, setDraggingImages] = useState(false);
  const imageInputRef = useRef<HTMLInputElement>(null);

  const completeness = useMemo(() => contextCompleteness(value), [value]);
  const detectedType = detectInputType(value.sqlText);

  function update<K extends keyof SqlInputDraft>(key: K, next: SqlInputDraft[K]) {
    setValue((current) => ({ ...current, [key]: next }));
  }

  function toggleAction(action: string) {
    setValue((current) => ({
      ...current,
      allowedActions: current.allowedActions.includes(action)
        ? current.allowedActions.filter((item) => item !== action)
        : [...current.allowedActions, action]
    }));
  }

  function submit() {
    if (!value.sqlText.trim() || loading) {
      return;
    }
    onSubmit({
      ...value,
      inputType: detectInputType(value.sqlText),
      planImages: value.planImages.map(({ name, dataUrl }) => ({ name, dataUrl }))
    });
    setValue((current) => ({
      ...current,
      sqlText: "",
      schemaText: "",
      indexText: "",
      explainText: "",
      businessContext: "",
      tableStatsText: "",
      runtimeMetricsText: "",
      businessInvariants: "",
      planImages: []
    }));
    setImageError("");
  }

  async function addImageFiles(files: File[]) {
    if (files.length === 0) {
      return;
    }

    const errors: string[] = [];
    const supportedFiles = files.filter((file) => {
      if (SUPPORTED_IMAGE_TYPES.has(normalizeImageType(file.type))) {
        return true;
      }
      errors.push(`${file.name || "剪贴板图片"}：仅支持 PNG、JPEG、WebP`);
      return false;
    });
    const availableSlots = Math.max(0, MAX_IMAGE_COUNT - value.planImages.length);
    if (supportedFiles.length > availableSlots) {
      errors.push(`最多添加 ${MAX_IMAGE_COUNT} 张图片，已忽略超出部分`);
    }

    const prepared: PreparedPlanImage[] = [];
    for (const file of supportedFiles.slice(0, availableSlots)) {
      try {
        prepared.push(await preparePlanImage(file, value.planImages.length + prepared.length + 1));
      } catch (error) {
        errors.push(error instanceof Error ? error.message : `${file.name || "图片"} 处理失败`);
      }
    }

    let projectedBytes = value.planImages.reduce((sum, image) => sum + image.size, 0);
    const withinTotalLimit = prepared.filter((image) => {
      if (projectedBytes + image.size > MAX_TOTAL_IMAGE_BYTES) {
        errors.push(`${image.name}：图片总大小不能超过 5 MiB`);
        return false;
      }
      projectedBytes += image.size;
      return true;
    });
    setValue((current) => {
      const remainingSlots = MAX_IMAGE_COUNT - current.planImages.length;
      let totalBytes = current.planImages.reduce((sum, image) => sum + image.size, 0);
      const accepted = withinTotalLimit.slice(0, remainingSlots).filter((image) => {
        const canAdd = totalBytes + image.size <= MAX_TOTAL_IMAGE_BYTES;
        totalBytes += canAdd ? image.size : 0;
        return canAdd;
      });
      return { ...current, planImages: [...current.planImages, ...accepted] };
    });
    setImageError(errors.join("；"));
  }

  function handlePaste(event: React.ClipboardEvent<HTMLElement>) {
    const imageFiles = Array.from(event.clipboardData.items)
      .filter((item) => item.kind === "file")
      .map((item) => item.getAsFile())
      .filter((file): file is File => Boolean(file));
    if (imageFiles.length > 0) {
      event.preventDefault();
      void addImageFiles(imageFiles);
    }
  }

  function handleDrop(event: React.DragEvent<HTMLDivElement>) {
    event.preventDefault();
    setDraggingImages(false);
    void addImageFiles(Array.from(event.dataTransfer.files));
  }

  function removeImage(index: number) {
    setValue((current) => ({
      ...current,
      planImages: current.planImages.filter((_, imageIndex) => imageIndex !== index)
    }));
    setImageError("");
  }

  return (
    <section className={compact ? "sql-input-panel compact" : "sql-input-panel"} onPaste={handlePaste}>
      <div className="composer-head">
        <div>
          <span>SQL / Inspection Report</span>
          <strong>粘贴 SQL 或整段巡检报告</strong>
        </div>
        <div className={`context-meter level-${completeness.level}`}>
          <Gauge size={15} />
          <span>{completeness.label}</span>
          <em>{completeness.score}/5</em>
        </div>
      </div>

      <div className="editor-frame">
        <CodeMirror
          value={value.sqlText}
          height={compact ? "176px" : "240px"}
          extensions={[
            sql(),
            EditorView.contentAttributes.of({ "aria-label": "SQL 或巡检报告文本" })
          ]}
          theme={document.documentElement.getAttribute("data-theme") === "dark" ? oneDark : "light"}
          basicSetup={{
            foldGutter: true,
            lineNumbers: true,
            highlightActiveLine: false
          }}
          onChange={(next) => update("sqlText", next)}
          placeholder="直接粘贴 SELECT / INSERT / UPDATE / DELETE，或从 SQL ID:、SQL: 开始的整段巡检报告。"
        />
      </div>

      <div
        className={draggingImages ? "image-evidence-zone dragging" : "image-evidence-zone"}
        onDragEnter={(event) => {
          event.preventDefault();
          setDraggingImages(true);
        }}
        onDragOver={(event) => event.preventDefault()}
        onDragLeave={(event) => {
          if (!event.currentTarget.contains(event.relatedTarget as Node | null)) {
            setDraggingImages(false);
          }
        }}
        onDrop={handleDrop}
        aria-label="图片证据区"
      >
        <input
          ref={imageInputRef}
          className="sr-only"
          type="file"
          accept="image/png,image/jpeg,image/webp"
          multiple
          aria-label="选择执行计划图片"
          onChange={(event) => {
            void addImageFiles(Array.from(event.currentTarget.files || []));
            event.currentTarget.value = "";
          }}
        />
        <div className="image-evidence-intro">
          <ImagePlus size={18} />
          <div>
            <strong>图片证据</strong>
            <span>拖入或粘贴执行计划截图，最多 3 张</span>
          </div>
          <button className="ghost-button" type="button" onClick={() => imageInputRef.current?.click()}>
            <Upload size={15} />
            选择图片
          </button>
        </div>
        {value.planImages.length > 0 && (
          <div className="image-evidence-list" aria-label="已添加图片">
            {value.planImages.map((image, index) => (
              <article key={`${image.name}-${index}`}>
                <img src={image.dataUrl} alt="" />
                <div>
                  <strong title={image.name}>{image.name}</strong>
                  <span>{formatBytes(image.size)}</span>
                </div>
                <button type="button" onClick={() => removeImage(index)} aria-label={`删除 ${image.name}`} title="删除图片">
                  <Trash2 size={15} />
                </button>
              </article>
            ))}
          </div>
        )}
        <p className="image-evidence-note">
          图片由视觉模型提取，不能代替文本 EXPLAIN，不会自动提高索引 DDL 置信度。
        </p>
        {imageError && <div className="form-error image-error" role="alert">{imageError}</div>}
      </div>

      <div className="input-toolbar">
        <div className="analysis-options">
          <button className={value.deepAnalysis ? "toggle active" : "toggle"} onClick={() => update("deepAnalysis", !value.deepAnalysis)} type="button">
            <Sparkles size={15} />
            {value.deepAnalysis ? "深度复核" : "标准分析"}
          </button>
          <select value={value.dbDialect} onChange={(event) => update("dbDialect", event.target.value as SqlDialect)} aria-label="数据库方言">
            <option value="OceanBase MySQL">OB MySQL</option>
            <option value="OceanBase Oracle">OB Oracle</option>
          </select>
          <input value={value.obVersion} onChange={(event) => update("obVersion", event.target.value)} placeholder="OB 版本" aria-label="OceanBase 版本" />
          <span className="input-type-badge">{inputTypeLabel(detectedType)}</span>
        </div>
        <div className="analysis-actions">
          <span className="input-meter">{value.sqlText.trim().length} / 32768</span>
          <button className="ghost-button" onClick={() => setContextOpen((open) => !open)} type="button" aria-expanded={contextOpen}>
            <Layers size={15} />
            {contextOpen ? "收起证据" : "补充证据"}
          </button>
          <button className="send-button" disabled={loading || !value.sqlText.trim()} onClick={submit} title="提交分析" aria-label="提交分析" type="button">
            <Send size={17} />
            {loading ? "提交中" : "开始诊断"}
          </button>
        </div>
      </div>

      {contextOpen && (
        <div className="context-grid">
          <ContextField
            icon={<Database size={14} />}
            label="表结构"
            value={value.schemaText}
            placeholder="CREATE TABLE / 字段类型 / 主键 / 分区"
            onChange={(next) => update("schemaText", next)}
          />
          <ContextField
            icon={<FileText size={14} />}
            label="当前索引"
            value={value.indexText}
            placeholder="SHOW INDEX / 索引列顺序 / 唯一性"
            onChange={(next) => update("indexText", next)}
          />
          <ContextField
            icon={<FileText size={14} />}
            label="EXPLAIN"
            value={value.explainText}
            placeholder="粘贴 EXPLAIN / 计划文本"
            onChange={(next) => update("explainText", next)}
          />
          <ContextField
            icon={<Activity size={14} />}
            label="运行指标"
            value={value.runtimeMetricsText}
            placeholder="耗时、扫描行、返回行、RPC、租户资源"
            onChange={(next) => update("runtimeMetricsText", next)}
          />
          <ContextField
            icon={<ListChecks size={14} />}
            label="表统计"
            value={value.tableStatsText}
            placeholder="行数、基数、热点值、分区分布"
            onChange={(next) => update("tableStatsText", next)}
          />
          <ContextField
            icon={<ShieldCheck size={14} />}
            label="业务语义约束"
            value={value.businessInvariants}
            placeholder="不能改变过滤条件、排序、分页、锁语义等"
            onChange={(next) => update("businessInvariants", next)}
          />
          <ContextField
            icon={<FileText size={14} />}
            label="业务说明"
            value={value.businessContext}
            placeholder="慢在哪里、期望耗时、调用场景"
            onChange={(next) => update("businessContext", next)}
          />
          <fieldset className="action-field">
            <legend>允许的建议类型</legend>
            <div className="action-options">
              {actionOptions.map((option) => (
                <label key={option.value}>
                  <input
                    type="checkbox"
                    checked={value.allowedActions.includes(option.value)}
                    onChange={() => toggleAction(option.value)}
                  />
                  <span>{option.label}</span>
                </label>
              ))}
            </div>
          </fieldset>
        </div>
      )}
    </section>
  );
}

function ContextField({
  icon,
  label,
  value,
  placeholder,
  onChange
}: {
  icon: React.ReactNode;
  label: string;
  value: string;
  placeholder: string;
  onChange: (value: string) => void;
}) {
  return (
    <label>
      <span>
        {icon}
        {label}
      </span>
      <textarea value={value} onChange={(event) => onChange(event.target.value)} placeholder={placeholder} />
    </label>
  );
}

function detectInputType(input: string): SqlInputValue["inputType"] {
  const normalized = input.trim().toLowerCase();
  if (/^sql(?:\s+id)?\s*[:：]/i.test(normalized)) {
    return "report_text";
  }
  if (/^(select|update|delete|insert|with)\b/.test(normalized)) {
    return "sql";
  }
  return "natural_language";
}

function inputTypeLabel(inputType: SqlInputValue["inputType"]) {
  if (inputType === "sql") {
    return "SQL";
  }
  if (inputType === "report_text") {
    return "报告文本";
  }
  return "自然语言";
}

function normalizeImageType(type: string) {
  return type.toLowerCase() === "image/jpg" ? "image/jpeg" : type.toLowerCase();
}

async function preparePlanImage(file: File, fallbackIndex: number): Promise<PreparedPlanImage> {
  const mediaType = normalizeImageType(file.type);
  const sourceDataUrl = await readFileAsDataUrl(file);
  const sourceBytes = dataUrlByteSize(sourceDataUrl);
  const name = sanitizeImageName(file.name || `剪贴板图片-${fallbackIndex}.${extensionForType(mediaType)}`);

  if (sourceBytes > 0 && sourceBytes <= MAX_IMAGE_BYTES) {
    return { name, dataUrl: sourceDataUrl, size: sourceBytes };
  }

  const image = await loadBrowserImage(sourceDataUrl);
  const scale = Math.min(1, MAX_IMAGE_EDGE / Math.max(image.naturalWidth, image.naturalHeight));
  const canvas = document.createElement("canvas");
  canvas.width = Math.max(1, Math.round(image.naturalWidth * scale));
  canvas.height = Math.max(1, Math.round(image.naturalHeight * scale));
  const context = canvas.getContext("2d");
  if (!context) {
    throw new Error(`${name}：浏览器无法压缩图片`);
  }
  context.imageSmoothingEnabled = true;
  context.imageSmoothingQuality = "high";
  context.drawImage(image, 0, 0, canvas.width, canvas.height);

  const encodings: Array<{ type: string; quality?: number }> = mediaType === "image/png"
    ? [
        { type: "image/png" },
        { type: "image/webp", quality: 0.95 },
        { type: "image/webp", quality: 0.9 },
        { type: "image/jpeg", quality: 0.94 },
        { type: "image/jpeg", quality: 0.88 }
      ]
    : [
        { type: mediaType, quality: 0.95 },
        { type: mediaType, quality: 0.9 },
        { type: mediaType, quality: 0.82 },
        { type: "image/jpeg", quality: 0.88 }
      ];

  for (const encoding of encodings) {
    const dataUrl = canvas.toDataURL(encoding.type, encoding.quality);
    const size = dataUrlByteSize(dataUrl);
    if (SUPPORTED_IMAGE_TYPES.has(dataUrlMediaType(dataUrl)) && size > 0 && size <= MAX_IMAGE_BYTES) {
      return { name, dataUrl, size };
    }
  }
  throw new Error(`${name}：压缩后仍超过 2 MiB，请裁剪后重试`);
}

function readFileAsDataUrl(file: File) {
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => typeof reader.result === "string" ? resolve(reader.result) : reject(new Error("图片读取失败"));
    reader.onerror = () => reject(new Error(`${file.name || "图片"} 读取失败`));
    reader.readAsDataURL(file);
  });
}

function loadBrowserImage(dataUrl: string) {
  return new Promise<HTMLImageElement>((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = () => reject(new Error("图片无法解码或已损坏"));
    image.src = dataUrl;
  });
}

function dataUrlByteSize(dataUrl: string) {
  const commaIndex = dataUrl.indexOf(",");
  if (commaIndex < 0) {
    return 0;
  }
  const base64 = dataUrl.slice(commaIndex + 1);
  const padding = base64.endsWith("==") ? 2 : base64.endsWith("=") ? 1 : 0;
  return Math.max(0, Math.floor(base64.length * 3 / 4) - padding);
}

function dataUrlMediaType(dataUrl: string) {
  const match = /^data:([^;,]+);base64,/i.exec(dataUrl);
  return normalizeImageType(match?.[1] || "");
}

function extensionForType(type: string) {
  if (type === "image/jpeg") {
    return "jpg";
  }
  return type === "image/webp" ? "webp" : "png";
}

function sanitizeImageName(name: string) {
  return name.replace(/[\r\n]/g, " ").trim().slice(0, 255) || "执行计划图片";
}

function formatBytes(bytes: number) {
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  return `${(bytes / 1024).toFixed(bytes < 1024 * 100 ? 1 : 0)} KiB`;
}

function contextCompleteness(value: SqlInputValue) {
  const score = [
    value.sqlText.trim(),
    value.schemaText.trim(),
    value.indexText.trim(),
    value.explainText.trim(),
    value.obVersion.trim() || value.tableStatsText.trim() || value.runtimeMetricsText.trim()
  ].filter(Boolean).length;
  if (score <= 1) {
    return { score, level: 1, label: "仅 SQL，低置信" };
  }
  if (score === 2) {
    return { score, level: 2, label: "可做改写前提" };
  }
  if (score === 3) {
    return { score, level: 3, label: "索引方向中置信" };
  }
  return { score, level: 4, label: "证据较完整" };
}
