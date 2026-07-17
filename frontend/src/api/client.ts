import type {
  ApiResponse,
  Conversation,
  HarnessArtifact,
  Message,
  ModelConfigView,
  ModelCatalogView,
  ModelProviderOption,
  ModelTestResult,
  PlanImage,
  ReadinessView,
  RuleFinding,
  RuntimeHealthView,
  SkillVersion,
  SqlDialect,
  SqlTuningTask,
  UserView
} from "../types/api";

const jsonHeaders = {
  "Content-Type": "application/json"
};

let csrfHeaderName = "X-XSRF-TOKEN";
let csrfToken = readCookie("XSRF-TOKEN");

async function request<T>(url: string, options: RequestInit = {}): Promise<T> {
  const method = (options.method || "GET").toUpperCase();
  const unsafe = !["GET", "HEAD", "OPTIONS", "TRACE"].includes(method);
  if (unsafe && !csrfToken) {
    await loadCsrfToken();
  }
  const response = await fetch(url, {
    credentials: "include",
    ...options,
    headers: {
      ...(options.body ? jsonHeaders : {}),
      ...(unsafe && csrfToken ? { [csrfHeaderName]: csrfToken } : {}),
      ...(options.headers || {})
    }
  });
  const payload = (await response.json()) as ApiResponse<T> & { code?: string; requestId?: string; details?: unknown };
  if (!response.ok || !payload.success) {
    const message = payload.code ? `${payload.code}: ${payload.message || "请求失败"}` : payload.message || "请求失败";
    throw new Error(payload.requestId ? `${message} (${payload.requestId})` : message);
  }
  return payload.data;
}

async function loadCsrfToken() {
  const response = await fetch("/api/auth/csrf", { credentials: "include" });
  const payload = (await response.json()) as ApiResponse<{ headerName?: string; token?: string }>;
  csrfHeaderName = payload.data?.headerName || csrfHeaderName;
  csrfToken = payload.data?.token || readCookie("XSRF-TOKEN");
}

function readCookie(name: string) {
  const match = document.cookie.match(new RegExp(`(?:^|; )${name}=([^;]*)`));
  return match ? decodeURIComponent(match[1]) : "";
}

export const csrf = {
  load: loadCsrfToken
};

export const api = {
  async login(username: string, password: string) {
    const user = await request<UserView>("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({ username, password })
    });
    // 登录会轮换 Session ID；随后重新获取 CSRF，避免继续复用登录前 token。
    csrfToken = "";
    await loadCsrfToken();
    return user;
  },
  async logout() {
    const result = await request<boolean>("/api/auth/logout", { method: "POST" });
    csrfToken = "";
    return result;
  },
  me() {
    return request<UserView | null>("/api/auth/me");
  },
  conversations() {
    return request<Conversation[]>("/api/conversations");
  },
  createConversation(title?: string) {
    return request<Conversation>("/api/conversations", {
      method: "POST",
      body: JSON.stringify({ title })
    });
  },
  messages(conversationId: number) {
    return request<Message[]>(`/api/conversations/${conversationId}/messages`);
  },
  deleteConversation(conversationId: number) {
    return request<boolean>(`/api/conversations/${conversationId}`, {
      method: "DELETE"
    });
  },
  createTask(input: {
    conversationId?: number;
    dbDialect: SqlDialect;
    sqlText: string;
    inputType?: string;
    schemaText?: string;
    indexText?: string;
    explainText?: string;
    businessContext?: string;
    obVersion?: string;
    tableStatsText?: string;
    runtimeMetricsText?: string;
    businessInvariants?: string;
    allowedActions?: string[];
    planImages?: PlanImage[];
    deepAnalysis: boolean;
  }, idempotencyKey?: string) {
    return request<SqlTuningTask>("/api/tuning/tasks", {
      method: "POST",
      headers: idempotencyKey ? { "Idempotency-Key": idempotencyKey } : undefined,
      body: JSON.stringify(input)
    });
  },
  task(taskId: number) {
    return request<SqlTuningTask>(`/api/tuning/tasks/${taskId}`);
  },
  streamTask(taskId: number, handlers: {
    onOpen?: () => void;
    onTask: (task: SqlTuningTask) => void;
    onArtifact?: (artifact: HarnessArtifact) => void;
    onError?: () => void;
  }) {
    const source = new EventSource(`/api/tuning/tasks/${taskId}/events`, { withCredentials: true });
    const taskEvent = (event: Event) => {
      const data = JSON.parse((event as MessageEvent<string>).data) as SqlTuningTask;
      handlers.onTask(data);
    };
    for (const name of ["snapshot", "status", "result", "task-error"]) {
      source.addEventListener(name, taskEvent);
    }
    source.addEventListener("artifact", (event) => {
      if (handlers.onArtifact) {
        handlers.onArtifact(JSON.parse((event as MessageEvent<string>).data) as HarnessArtifact);
      }
    });
    source.onopen = () => handlers.onOpen?.();
    source.onerror = () => handlers.onError?.();
    return () => source.close();
  },
  skills() {
    return request<SkillVersion[]>("/api/admin/skills");
  },
  saveSkill(name: string, content: string) {
    return request<SkillVersion>("/api/admin/skills", {
      method: "POST",
      body: JSON.stringify({ name, content })
    });
  },
  modelConfig() {
    return request<ModelConfigView>("/api/admin/model-config");
  },
  runtimeHealth() {
    return request<RuntimeHealthView>("/api/admin/health");
  },
  readiness() {
    return request<ReadinessView>("/api/health/ready");
  },
  modelProviders() {
    return request<ModelProviderOption[]>("/api/admin/model-providers");
  },
  updateModelConfig(input: { provider?: string; baseUrl?: string; model?: string; visionModel?: string; apiKey?: string; timeoutMs?: number }) {
    return request<ModelConfigView>("/api/admin/model-config", {
      method: "POST",
      body: JSON.stringify(input)
    });
  },
  testModelConfig() {
    return request<ModelTestResult>("/api/admin/model-config/test", {
      method: "POST"
    });
  },
  discoverModels(input: { baseUrl?: string; apiKey?: string }) {
    return request<ModelCatalogView>("/api/admin/model-config/models", {
      method: "POST",
      body: JSON.stringify(input)
    });
  },
  rules() {
    return request<RuleFinding[]>("/api/admin/rules");
  }
};
