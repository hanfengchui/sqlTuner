import type {
  ApiResponse,
  Conversation,
  Message,
  ModelConfigView,
  ModelProviderOption,
  ModelTestResult,
  RuleFinding,
  SkillVersion,
  SqlDialect,
  SqlTuningTask,
  UserView
} from "../types/api";

const jsonHeaders = {
  "Content-Type": "application/json"
};

async function request<T>(url: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(url, {
    credentials: "include",
    ...options,
    headers: {
      ...(options.body ? jsonHeaders : {}),
      ...(options.headers || {})
    }
  });
  const payload = (await response.json()) as ApiResponse<T>;
  if (!response.ok || !payload.success) {
    throw new Error(payload.message || "请求失败");
  }
  return payload.data;
}

export const api = {
  login(username: string, password: string) {
    return request<UserView>("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({ username, password })
    });
  },
  logout() {
    return request<boolean>("/api/auth/logout", { method: "POST" });
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
    deepAnalysis: boolean;
  }) {
    return request<SqlTuningTask>("/api/tuning/tasks", {
      method: "POST",
      body: JSON.stringify(input)
    });
  },
  task(taskId: number) {
    return request<SqlTuningTask>(`/api/tuning/tasks/${taskId}`);
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
  modelProviders() {
    return request<ModelProviderOption[]>("/api/admin/model-providers");
  },
  updateModelConfig(input: { provider?: string; baseUrl?: string; model?: string; apiKey?: string; timeoutMs?: number }) {
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
  rules() {
    return request<RuleFinding[]>("/api/admin/rules");
  }
};
