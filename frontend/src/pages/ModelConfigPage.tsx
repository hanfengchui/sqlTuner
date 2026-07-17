import { Bot, CheckCircle2, Clock3, KeyRound, Link2, PlugZap, ServerCog, ShieldCheck } from "lucide-react";
import { useEffect, useState } from "react";
import { api } from "../api/client";
import type { ModelConfigView, ModelProviderOption, ModelTestResult, ReadinessView, RuntimeHealthView } from "../types/api";

export function ModelConfigPage() {
  const [config, setConfig] = useState<ModelConfigView | undefined>();
  const [provider, setProvider] = useState("");
  const [baseUrl, setBaseUrl] = useState("");
  const [model, setModel] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [timeoutMs, setTimeoutMs] = useState("30000");
  const [message, setMessage] = useState("");
  const [providers, setProviders] = useState<ModelProviderOption[]>([]);
  const [testResult, setTestResult] = useState<ModelTestResult | undefined>();
  const [runtimeHealth, setRuntimeHealth] = useState<RuntimeHealthView | undefined>();
  const [readiness, setReadiness] = useState<ReadinessView | undefined>();
  const [testing, setTesting] = useState(false);

  useEffect(() => {
    api.modelProviders().then(setProviders);
    Promise.all([api.modelConfig(), api.runtimeHealth().catch(() => undefined), api.readiness().catch(() => undefined)]).then(([next, health, ready]) => {
      setConfig(next);
      setProvider(next.provider || "");
      setBaseUrl(next.baseUrl || "");
      setModel(next.model || "");
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
      apiKey: apiKey.trim() || undefined,
      timeoutMs: Number(timeoutMs || 30000)
    });
    setConfig(next);
    setApiKey("");
    setMessage("模型配置已保存");
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

  function changeProvider(nextProvider: string) {
    setProvider(nextProvider);
    const option = providers.find((item) => item.value === nextProvider);
    if (option) {
      setBaseUrl(option.defaultBaseUrl);
      setModel(option.defaultModel);
    }
  }

  return (
    <div className="admin-page model-page">
      <header className="page-header-row">
        <div>
          <span>Model Gateway</span>
          <h1>模型网关配置</h1>
          <p>配置千问兼容接口、模型、API Key 与超时。保存后后端立即使用，前端不回显密钥明文。</p>
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
          <p>{config?.provider || "dashscope"} · OpenAI Compatible</p>
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
              <strong>{readiness ? `${readiness.running || 0} running / ${readiness.queued || 0} queued` : "未读取"}</strong>
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
            <label>
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
              <span>Model</span>
              <input value={model} onChange={(event) => setModel(event.target.value)} placeholder="qwen-plus" />
            </label>
            <label className="span-2">
              <span>Base URL</span>
              <input value={baseUrl} onChange={(event) => setBaseUrl(event.target.value)} placeholder="https://dashscope.aliyuncs.com/compatible-mode/v1" />
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
                placeholder={config?.apiKeyConfigured ? "已配置，留空则保持不变" : "粘贴 DashScope API Key"}
                autoComplete="off"
              />
            </label>
          </div>
          <div className="callout-box">
            <KeyRound size={17} />
            <div>
              <strong>API Key 加密保存到 MySQL</strong>
              <span>页面提交后不会回显明文。留空保存时保持已有密钥；空库初始化时可从 DASHSCOPE_API_KEY 写入。</span>
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
        </section>
      </div>
    </div>
  );
}
