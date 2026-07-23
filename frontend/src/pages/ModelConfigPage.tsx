import { Bot, CheckCircle2, Clock3, KeyRound, Link2, Play, PlugZap, RefreshCw, ServerCog, ShieldCheck } from "lucide-react";
import { useEffect, useState } from "react";
import { api } from "../api/client";
import type { ModelConfigView, ModelPreviewResult, ModelProviderOption, ModelTestResult, ReadinessView, RuntimeHealthView } from "../types/api";

export function ModelConfigPage() {
  const [config, setConfig] = useState<ModelConfigView | undefined>();
  const [provider, setProvider] = useState("");
  const [baseUrl, setBaseUrl] = useState("");
  const [model, setModel] = useState("");
  const [visionModel, setVisionModel] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [timeoutMs, setTimeoutMs] = useState("30000");
  const [message, setMessage] = useState("");
  const [providers, setProviders] = useState<ModelProviderOption[]>([]);
  const [testResult, setTestResult] = useState<ModelTestResult | undefined>();
  const [runtimeHealth, setRuntimeHealth] = useState<RuntimeHealthView | undefined>();
  const [readiness, setReadiness] = useState<ReadinessView | undefined>();
  const [testing, setTesting] = useState(false);
  const [loadingModels, setLoadingModels] = useState(false);
  const [availableModels, setAvailableModels] = useState<string[]>([]);
  const [previewPrompt, setPreviewPrompt] = useState("");
  const [previewResult, setPreviewResult] = useState<ModelPreviewResult | undefined>();
  const [previewing, setPreviewing] = useState(false);

  useEffect(() => {
    api.modelProviders().then(setProviders);
    Promise.all([api.modelConfig(), api.runtimeHealth().catch(() => undefined), api.readiness().catch(() => undefined)]).then(([next, health, ready]) => {
      setConfig(next);
      setProvider(next.provider || "");
      setBaseUrl(next.baseUrl || "");
      setModel(next.model || "");
      setVisionModel(next.visionModel || next.model || "");
      setTimeoutMs(String(next.timeoutMs || 30000));
      setRuntimeHealth(health);
      setReadiness(ready);
    });
  }, []);

  async function save() {
    const next = await api.updateModelConfig({
      provider,
      baseUrl,
      model,
      visionModel,
      apiKey: apiKey.trim() || undefined,
      timeoutMs: Number(timeoutMs || 30000)
    });
    setConfig(next);
    setApiKey("");
    setMessage("模型配置已保存");
  }

  async function loadModels() {
    setLoadingModels(true);
    setMessage("");
    try {
      const catalog = await api.discoverModels({
        baseUrl,
        apiKey: apiKey.trim() || undefined
      });
      setAvailableModels(catalog.models);
      setMessage(`已从网关读取 ${catalog.models.length} 个模型`);
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "模型列表读取失败");
    } finally {
      setLoadingModels(false);
    }
  }

  async function testConnection() {
    setTesting(true);
    setTestResult(undefined);
    setMessage("");
    try {
      await save();
      const result = await api.testModelConfig();
      setTestResult(result);
      setMessage(result.success ? "连接测试成功" : "连接测试未通过");
    } finally {
      setTesting(false);
    }
  }

  async function previewModel() {
    if (!previewPrompt.trim()) {
      setMessage("请输入需要试跑的内容");
      return;
    }
    setPreviewing(true);
    setPreviewResult(undefined);
    setMessage("");
    try {
      const result = await api.previewModel({
        userPrompt: previewPrompt,
        deepAnalysis: true
      });
      setPreviewResult(result);
      setMessage(result.success ? "模型试跑完成" : "模型试跑未通过");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "模型试跑失败");
    } finally {
      setPreviewing(false);
    }
  }

  function changeProvider(nextProvider: string) {
    setProvider(nextProvider);
    const option = providers.find((item) => item.value === nextProvider);
    if (option) {
      setBaseUrl(option.defaultBaseUrl);
      setModel(option.defaultModel);
      setVisionModel(nextProvider === "dashscope" ? "qwen3-vl-plus" : option.defaultModel);
      setAvailableModels([]);
    }
  }

  return (
    <div className="admin-page model-page">
      <header className="page-header-row">
        <div>
          <span>Model Gateway</span>
          <h1>模型网关配置</h1>
          <p>配置任意 OpenAI-compatible API、分析模型、视觉模型、API Key 与超时。保存后立即生效，密钥不会回显。</p>
        </div>
        <div className={config?.apiKeyConfigured ? "health-pill ok" : "health-pill warn"}>
          {config?.apiKeyConfigured ? <CheckCircle2 size={16} /> : <KeyRound size={16} />}
          {config?.apiKeyConfigured ? "凭据已加载" : "凭据未配置"}
        </div>
      </header>

      <div className="model-console">
        <aside className="model-summary">
          <div className="model-mark">
            <Bot size={26} />
          </div>
          <h2>{config?.model || "qwen-plus"}</h2>
          <p>{config?.provider || "openai-compatible"} · OpenAI-compatible</p>
          <div className="model-stat-list">
            <div>
              <ServerCog size={16} />
              <span>Provider</span>
              <strong>{config?.provider || "loading"}</strong>
            </div>
            <div>
              <Link2 size={16} />
              <span>Endpoint</span>
              <strong>{config?.baseUrl || "未读取"}</strong>
            </div>
            <div>
              <Clock3 size={16} />
              <span>Timeout</span>
              <strong>{config?.timeoutMs || 30000} ms</strong>
            </div>
            <div>
              <ShieldCheck size={16} />
              <span>Runtime</span>
              <strong>{runtimeHealth?.mockState || config?.mockState || (config?.apiKeyConfigured ? "ready" : "missing-key")}</strong>
            </div>
            <div>
              <PlugZap size={16} />
              <span>Queue</span>
              <strong>{runtimeHealth ? `${runtimeHealth.running || 0} running / ${runtimeHealth.queued || 0} queued` : "未读取"}</strong>
            </div>
            <div>
              <CheckCircle2 size={16} />
              <span>MySQL</span>
              <strong>{readiness?.mysql || readiness?.status || "未读取"}</strong>
            </div>
          </div>
        </aside>

        <section className="config-editor-card">
          <div className="section-title-row">
            <div>
              <span>Runtime Settings</span>
              <h2>调用参数</h2>
            </div>
            {message && <em>{message}</em>}
          </div>
          <div className="field-grid">
            <label className="span-2">
              <span>Provider</span>
              <select value={provider} onChange={(event) => changeProvider(event.target.value)}>
                {providers.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
                {!providers.some((option) => option.value === provider) && <option value={provider}>{provider || "custom"}</option>}
              </select>
            </label>
            <label>
              <span>分析模型</span>
              <input list="available-models" value={model} onChange={(event) => setModel(event.target.value)} placeholder="选择或输入模型 ID" />
            </label>
            <label>
              <span>视觉模型</span>
              <input list="available-models" value={visionModel} onChange={(event) => setVisionModel(event.target.value)} placeholder="选择支持图片的模型 ID" />
            </label>
            <label className="span-2">
              <span>Base URL</span>
              <div className="endpoint-control">
                <input value={baseUrl} onChange={(event) => setBaseUrl(event.target.value)} placeholder="https://api.example.com/v1" />
                <button className="ghost-button" type="button" onClick={loadModels} disabled={loadingModels || provider === "mock"}>
                  <RefreshCw className={loadingModels ? "spin" : ""} size={15} />
                  {loadingModels ? "读取中" : "读取模型"}
                </button>
              </div>
            </label>
            <label>
              <span>Timeout(ms)</span>
              <input value={timeoutMs} onChange={(event) => setTimeoutMs(event.target.value)} placeholder="30000" />
            </label>
            <label>
              <span>API Key</span>
              <input
                value={apiKey}
                onChange={(event) => setApiKey(event.target.value)}
                type="password"
                placeholder={config?.apiKeyConfigured ? "已配置，留空则使用已保存密钥" : "粘贴 API Key"}
                autoComplete="off"
              />
            </label>
          </div>
          <datalist id="available-models">
            {availableModels.map((item) => <option key={item} value={item} />)}
          </datalist>
          <div className="callout-box">
            <KeyRound size={17} />
            <div>
              <strong>API Key 加密保存到 MySQL</strong>
              <span>“读取模型”调用网关标准 `/models` 接口；若网关不支持模型列表，仍可直接输入模型 ID。视觉模型只在上传截图时调用。</span>
            </div>
          </div>
          <div className="editor-actions">
            <span>{config?.apiKeyConfigured ? "可进行真实模型调用" : "真实 provider 缺少 API Key 时会显式拒绝调用"}</span>
            <div className="action-row">
              <button className="ghost-button" onClick={testConnection} disabled={testing}>
                <PlugZap size={16} />
                {testing ? "测试中..." : "测试连接"}
              </button>
              <button className="primary-button" onClick={save}>保存配置</button>
            </div>
          </div>
          {testResult && (
            <div className={testResult.success ? "test-result ok" : "test-result warn"}>
              <strong>{testResult.message}</strong>
              <span>
                {testResult.provider} / {testResult.model} · {testResult.elapsedMs}ms · {testResult.mock ? "mock" : "real"}
              </span>
              {testResult.sample && <code>{testResult.sample}</code>}
            </div>
          )}
          <details className="prompt-preview">
            <summary>模型试跑</summary>
            <p>仅管理员可用。输入只会发送给当前模型，不写入会话、任务或审计历史；用于比较提示词和输出风格。</p>
            <label>
              <span>试跑内容</span>
              <textarea
                aria-label="模型试跑内容"
                value={previewPrompt}
                onChange={(event) => setPreviewPrompt(event.target.value)}
                placeholder="粘贴 SQL、执行计划或巡检材料，直接查看模型的自由文本分析"
              />
            </label>
            <div className="editor-actions">
              <span>本次不启用 JSON 输出约束；不影响正式调优任务的安全策略。</span>
              <button className="ghost-button" type="button" onClick={previewModel} disabled={previewing || !previewPrompt.trim()}>
                <Play size={16} />
                {previewing ? "试跑中..." : "运行试跑"}
              </button>
            </div>
            {previewResult && (
              <div className={previewResult.success ? "test-result ok" : "test-result warn"}>
                <strong>{previewResult.message}</strong>
                <span>{previewResult.provider} / {previewResult.model} · {previewResult.elapsedMs}ms · {previewResult.mock ? "mock" : "real"}</span>
                {previewResult.output && <pre>{previewResult.output}</pre>}
              </div>
            )}
          </details>
        </section>
      </div>
    </div>
  );
}
