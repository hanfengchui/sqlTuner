import { expect, test } from "@playwright/test";

test("login surface renders with command-center branding", async ({ page }) => {
  await page.route("**/api/auth/me", async (route) => {
    await route.fulfill({ json: { success: true, data: null } });
  });

  await page.goto("/login");

  await expect(page.getByRole("heading", { name: /SQL/ })).toBeVisible();
  await expect(page.getByLabel("用户名")).toBeVisible();
  await expect(page.getByLabel("密码")).toBeVisible();
});

test("workspace shell renders session search and composer", async ({ page }) => {
  await page.route("**/api/auth/me", async (route) => {
    await route.fulfill({
      json: { success: true, data: { id: 1, username: "admin", displayName: "Admin", role: "ADMIN" } }
    });
  });
  await page.route("**/api/conversations", async (route) => {
    await route.fulfill({
      json: {
        success: true,
        data: [{ id: 1, userId: 1, title: "订单慢 SQL", createdAt: "2026-07-17T00:00:00Z", updatedAt: "2026-07-17T00:00:00Z" }]
      }
    });
  });
  await page.route("**/api/conversations/1/messages", async (route) => {
    await route.fulfill({ json: { success: true, data: [] } });
  });

  await page.goto("/chat");

  await expect(page.getByText("SQL 调优助手").first()).toBeVisible();
  await expect(page.getByPlaceholder("搜索会话标题")).toBeVisible();
  await expect(page.getByLabel("SQL 或巡检报告文本")).toBeVisible();
  await expect(page.getByLabel("SQL 调优消息编辑器")).toBeVisible();
  await expect(page.getByRole("button", { name: "添加执行计划截图" })).toBeVisible();
  await expect(page.getByRole("button", { name: "补充证据" })).toHaveCount(0);
});

test("workspace renders concise validated advice inline in the conversation", async ({ page }, testInfo) => {
  await page.route("**/api/auth/me", async (route) => {
    await route.fulfill({
      json: { success: true, data: { id: 1, username: "admin", displayName: "Admin", role: "ADMIN" } }
    });
  });
  await page.route("**/api/conversations", async (route) => {
    await route.fulfill({
      json: {
        success: true,
        data: [{ id: 1, userId: 1, title: "订单慢 SQL", createdAt: "2026-07-18T00:00:00Z", updatedAt: "2026-07-18T00:00:00Z" }]
      }
    });
  });
  await page.route("**/api/conversations/1/messages", async (route) => {
    await route.fulfill({
      json: {
        success: true,
        data: [
          { id: 1, conversationId: 1, role: "USER", content: "select * from orders where status = 'PAID' order by created_at desc", taskId: 7, createdAt: "2026-07-18T00:00:00Z" },
          { id: 2, conversationId: 1, role: "ASSISTANT", content: "当前查询可通过缩小投影列并验证排序访问路径来降低扫描成本。", taskId: 7, createdAt: "2026-07-18T00:00:05Z" }
        ]
      }
    });
  });
  await page.route("**/api/tuning/tasks/7", async (route) => {
    await route.fulfill({
      json: {
        success: true,
        data: {
          id: 7,
          userId: 1,
          conversationId: 1,
          dbDialect: "OceanBase MySQL",
          originalSql: "select * from orders where status = 'PAID' order by created_at desc",
          deepAnalysis: false,
          status: "DONE",
          statusMessage: "调优建议已生成",
          ruleFindings: [],
          artifacts: [],
          createdAt: "2026-07-18T00:00:00Z",
          updatedAt: "2026-07-18T00:00:05Z",
          result: {
            outcome: "ADVICE",
            summary: "当前查询可通过缩小投影列并验证排序访问路径来降低扫描成本。",
            analysisNarrative: {
              conclusion: "最终结论：先确认当前访问路径和索引覆盖情况；在没有计划证据前，不应直接把排序问题归因于索引缺失。",
              sections: [
                {
                  kind: "EVIDENCE",
                  title: "为什么先验证计划",
                  body: "- 现有输入可以确认筛选和排序结构。\n- 还不能确认实际扫描行数、排序算子或索引命中情况。",
                  evidenceRefs: ["E_EXPLAIN"]
                },
                {
                  kind: "ACTION",
                  title: "可先评估的改动",
                  body: "- 若调用方不依赖全部列，可验证缩小投影列后的结果集和执行计划差异。",
                  evidenceRefs: ["E_EXPLAIN"]
                },
                {
                  kind: "VALIDATION",
                  title: "验证标准",
                  body: "- 对比改动前后的 EXPLAIN。\n- 确认排序访问路径和返回结果保持一致。",
                  evidenceRefs: ["E_EXPLAIN"]
                }
              ]
            },
            evidenceCatalog: [{ id: "E_EXPLAIN", source: "USER_EXPLAIN", summary: "TABLE ACCESS BY INDEX", trustLevel: "HIGH" }],
            diagnoses: [{ title: "SELECT * 扩大回表成本", impact: "返回列过多会增加 I/O" }],
            rewriteCandidates: [{ sql: "select id, status, created_at from orders where status = ? order by created_at desc", change: "仅保留调用方实际使用的列" }],
            indexCandidates: [{
              tableName: "orders",
              columnOrder: ["status", "created_at"],
              ddl: "create index idx_orders_status_created on orders(status, created_at)",
              benefit: "仅在确认当前索引不覆盖排序时评估"
            }],
            validationPlan: [{ action: "执行 EXPLAIN", expectedSignal: "确认排序是否命中现有索引" }],
            missingInformation: [],
            safetyWarnings: [],
            findings: [],
            rewriteSql: "",
            indexSuggestions: [],
            validationSteps: [],
            riskWarnings: [],
            needMoreInfo: [],
            rawModelOutput: "",
            mockModel: false
          }
        }
      }
    });
  });

  await page.goto("/chat");

  await expect(page.getByText("先确认当前访问路径和索引覆盖情况；在没有计划证据前，不应直接把排序问题归因于索引缺失。")).toBeVisible();
  await expect(page.getByText("为什么先验证计划")).toBeVisible();
  await expect(page.getByText("验证标准")).toBeVisible();
  await expect(page.getByText("建议改写")).toBeVisible();
  await expect(page.getByText("索引候选")).toHaveCount(0);
  await expect(page.getByText(/create index idx_orders_status_created/)).toHaveCount(0);
  await expect(page.getByText("重点问题")).toHaveCount(0);
  await expect(page.getByText("SELECT * 扩大回表成本")).toHaveCount(0);
  await expect(page.getByRole("tab")).toHaveCount(0);
  await expect(page.getByText("打开右侧报告")).toHaveCount(0);
  await expect(page.getByRole("link", { name: "查看完整依据" })).toHaveCount(0);
  await page.screenshot({ path: testInfo.outputPath("inline-advice.png"), fullPage: true });
});

test("model gateway discovers OpenAI-compatible models and keeps custom input", async ({ page }) => {
  await page.route("**/api/auth/me", async (route) => {
    await route.fulfill({
      json: { success: true, data: { id: 1, username: "admin", displayName: "Admin", role: "ADMIN" } }
    });
  });
  await page.route("**/api/conversations", async (route) => {
    await route.fulfill({ json: { success: true, data: [] } });
  });
  await page.route("**/api/admin/model-providers", async (route) => {
    await route.fulfill({
      json: {
        success: true,
        data: [{
          value: "openai-compatible",
          label: "OpenAI-compatible API（通用）",
          defaultBaseUrl: "https://gateway.example.com/v1",
          defaultModel: "model-a",
          requiresApiKey: true
        }]
      }
    });
  });
  await page.route("**/api/admin/model-config", async (route) => {
    await route.fulfill({
      json: {
        success: true,
        data: {
          provider: "openai-compatible",
          baseUrl: "https://gateway.example.com/v1",
          model: "model-a",
          visionModel: "vision-a",
          timeoutMs: 30000,
          apiKeyConfigured: true,
          mockState: "ready"
        }
      }
    });
  });
  await page.route("**/api/admin/health", async (route) => {
    await route.fulfill({ json: { success: true, data: { provider: "openai-compatible", model: "model-a", mockState: "ready", apiKeyConfigured: true } } });
  });
  await page.route("**/api/health/ready", async (route) => {
    await route.fulfill({ json: { success: true, data: { status: "UP", mysql: "UP", queued: 0, running: 0 } } });
  });
  await page.route("**/api/auth/csrf", async (route) => {
    await route.fulfill({ json: { success: true, data: { headerName: "X-XSRF-TOKEN", token: "test-token" } } });
  });
  await page.route("**/api/admin/model-config/models", async (route) => {
    await route.fulfill({
      json: { success: true, data: { endpoint: "https://gateway.example.com/v1/models", models: ["model-a", "model-b", "vision-a"] } }
    });
  });

  await page.goto("/admin/model");
  await page.getByRole("button", { name: "读取模型" }).click();

  await expect(page.getByText("已从网关读取 3 个模型")).toBeVisible();
  await expect(page.locator('#available-models option[value="model-b"]')).toHaveCount(1);
  await page.getByLabel("分析模型").fill("custom-model-id");
  await expect(page.getByLabel("分析模型")).toHaveValue("custom-model-id");
  await expect(page.getByLabel("视觉模型")).toHaveValue("vision-a");
});
