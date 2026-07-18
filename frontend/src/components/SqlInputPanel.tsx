import { ImagePlus, SendHorizontal, Sparkles, Trash2 } from "lucide-react";
import type React from "react";
import { useRef, useState } from "react";
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
  planImages: PlanImage[];
  deepAnalysis: boolean;
}

interface SqlInputPanelProps {
  loading: boolean;
  onSubmit: (value: SqlInputValue) => void | Promise<void>;
}

export function SqlInputPanel({ loading, onSubmit }: SqlInputPanelProps) {
  const [dbDialect, setDbDialect] = useState<SqlDialect>("OceanBase MySQL");
  const [sqlText, setSqlText] = useState("");
  const [deepAnalysis, setDeepAnalysis] = useState(false);
  const [planImages, setPlanImages] = useState<PreparedPlanImage[]>([]);
  const [imageError, setImageError] = useState("");
  const [draggingImages, setDraggingImages] = useState(false);
  const imageInputRef = useRef<HTMLInputElement>(null);

  async function submit() {
    if (!sqlText.trim() || loading) {
      return;
    }
    const nextValue: SqlInputValue = {
      dbDialect,
      sqlText,
      inputType: detectInputType(sqlText),
      planImages: planImages.map(({ name, dataUrl }) => ({ name, dataUrl })),
      deepAnalysis
    };
    try {
      await onSubmit(nextValue);
      setSqlText("");
      setPlanImages([]);
      setImageError("");
    } catch {
      // 页面层展示请求错误；草稿保留，避免长报告和截图在失败时丢失。
    }
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
    const availableSlots = Math.max(0, MAX_IMAGE_COUNT - planImages.length);
    if (supportedFiles.length > availableSlots) {
      errors.push(`最多添加 ${MAX_IMAGE_COUNT} 张截图，已忽略超出部分`);
    }

    const prepared: PreparedPlanImage[] = [];
    for (const file of supportedFiles.slice(0, availableSlots)) {
      try {
        prepared.push(await preparePlanImage(file, planImages.length + prepared.length + 1));
      } catch (error) {
        errors.push(error instanceof Error ? error.message : `${file.name || "图片"} 处理失败`);
      }
    }

    setPlanImages((current) => {
      const remainingSlots = MAX_IMAGE_COUNT - current.length;
      let totalBytes = current.reduce((sum, image) => sum + image.size, 0);
      const accepted = prepared.slice(0, remainingSlots).filter((image) => {
        if (totalBytes + image.size > MAX_TOTAL_IMAGE_BYTES) {
          errors.push(`${image.name}：图片总大小不能超过 5 MiB`);
          return false;
        }
        totalBytes += image.size;
        return true;
      });
      return [...current, ...accepted];
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
      const pastedText = typeof event.clipboardData.getData === "function"
        ? event.clipboardData.getData("text/plain")
        : "";
      const target = event.target instanceof HTMLTextAreaElement ? event.target : undefined;
      if (pastedText) {
        const selectionStart = target?.selectionStart ?? sqlText.length;
        const selectionEnd = target?.selectionEnd ?? sqlText.length;
        setSqlText((current) => `${current.slice(0, selectionStart)}${pastedText}${current.slice(selectionEnd)}`);
      }
      void addImageFiles(imageFiles);
    }
  }

  function handleDrop(event: React.DragEvent<HTMLElement>) {
    event.preventDefault();
    setDraggingImages(false);
    void addImageFiles(Array.from(event.dataTransfer.files));
  }

  function removeImage(index: number) {
    setPlanImages((current) => current.filter((_, imageIndex) => imageIndex !== index));
    setImageError("");
  }

  function handleKeyDown(event: React.KeyboardEvent<HTMLTextAreaElement>) {
    if ((event.metaKey || event.ctrlKey) && event.key === "Enter") {
      event.preventDefault();
      void submit();
    }
  }

  return (
    <section
      className={draggingImages ? "chat-composer dragging" : "chat-composer"}
      onPaste={handlePaste}
      onDragEnter={(event) => {
        if (event.dataTransfer.types.includes("Files")) {
          event.preventDefault();
          setDraggingImages(true);
        }
      }}
      onDragOver={(event) => event.preventDefault()}
      onDragLeave={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget as Node | null)) {
          setDraggingImages(false);
        }
      }}
      onDrop={handleDrop}
      aria-label="SQL 调优消息编辑器"
    >
      <input
        ref={imageInputRef}
        className="sr-only"
        type="file"
        accept="image/png,image/jpeg,image/webp"
        multiple
        aria-label="选择执行计划截图文件"
        onChange={(event) => {
          void addImageFiles(Array.from(event.currentTarget.files || []));
          event.currentTarget.value = "";
        }}
      />

      {planImages.length > 0 && (
        <div className="attachment-strip" aria-label="已添加的执行计划截图">
          {planImages.map((image, index) => (
            <article key={`${image.name}-${index}`}>
              <img src={image.dataUrl} alt="" />
              <div>
                <strong title={image.name}>{image.name}</strong>
                <span>{formatBytes(image.size)}</span>
              </div>
              <button type="button" onClick={() => removeImage(index)} aria-label={`删除 ${image.name}`} title="删除截图">
                <Trash2 size={14} />
              </button>
            </article>
          ))}
        </div>
      )}

      <textarea
        value={sqlText}
        onChange={(event) => setSqlText(event.target.value)}
        onKeyDown={handleKeyDown}
        aria-label="SQL 或巡检报告文本"
        placeholder="粘贴 SQL、巡检报告、DDL 或 EXPLAIN，直接分析"
        rows={5}
      />

      <footer className="chat-composer-footer">
        <div className="chat-composer-controls">
          <label className="composer-select">
            <span className="sr-only">数据库方言</span>
            <select value={dbDialect} onChange={(event) => setDbDialect(event.target.value as SqlDialect)} aria-label="数据库方言">
              <option value="OceanBase MySQL">OB MySQL</option>
              <option value="OceanBase Oracle">OB Oracle</option>
            </select>
          </label>
          <button
            className="composer-icon-button"
            type="button"
            onClick={() => imageInputRef.current?.click()}
            aria-label="添加执行计划截图"
            title="添加执行计划截图"
          >
            <ImagePlus size={17} />
            {planImages.length > 0 && <span>{planImages.length}</span>}
          </button>
          <button
            className={deepAnalysis ? "analysis-mode active" : "analysis-mode"}
            type="button"
            onClick={() => setDeepAnalysis((current) => !current)}
            aria-pressed={deepAnalysis}
            title={deepAnalysis ? "深度复核已开启" : "标准分析"}
          >
            <Sparkles size={16} />
            <span>{deepAnalysis ? "深度复核" : "标准分析"}</span>
          </button>
        </div>
        <button
          className="composer-send"
          disabled={loading || !sqlText.trim()}
          onClick={() => void submit()}
          title="提交分析"
          aria-label="提交分析"
          type="button"
        >
          <SendHorizontal size={18} />
        </button>
      </footer>
      {imageError && <div className="form-error composer-error" role="alert">{imageError}</div>}
    </section>
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
  return name.replace(/[\r\n]/g, " ").trim().slice(0, 255) || "执行计划截图";
}

function formatBytes(bytes: number) {
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  return `${(bytes / 1024).toFixed(bytes < 1024 * 100 ? 1 : 0)} KiB`;
}
