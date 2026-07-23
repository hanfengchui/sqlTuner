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
  await page.route("**/api/conversations/page**", async (route) => {
    await route.fulfill({
      json: {
        success: true,
        data: {
          items: [{ id: 1, userId: 1, title: "订单慢 SQL", createdAt: "2026-07-17T00:00:00Z", updatedAt: "2026-07-17T00:00:00Z" }],
          hasMore: false
        }
      }
    });
  });
  await page.route("**/api/conversations/1/timeline**", async (route) => {
    await route.fulfill({ json: { success: true, data: { items: [], hasMore: false } } });
  });

  await page.goto("/chat");

  await expect(page.getByText("SQL Tuner")).toBeVisible();
  await page.getByRole("button", { name: "搜索会话" }).click();
  await expect(page.getByPlaceholder("搜索会话标题")).toBeVisible();
  await expect(page.getByLabel("SQL 或巡检报告文本")).toBeVisible();
  await expect(page.getByLabel("SQL 调优消息编辑器")).toBeVisible();
  await expect(page.getByRole("button", { name: "添加执行计划截图" })).toBeVisible();
  await expect(page.getByRole("button", { name: "补充证据" })).toHaveCount(0);

  const desktopShell = await page.locator(".app-shell").evaluate((shell) => {
    const sidebar = shell.querySelector<HTMLElement>(".sidebar");
    const mainStage = shell.querySelector<HTMLElement>(".main-stage");
    const topbar = shell.querySelector<HTMLElement>(".topbar");
    const chatColumn = shell.querySelector<HTMLElement>(".chat-column");
    const composer = shell.querySelector<HTMLElement>(".chat-composer");
    const sidebarRect = sidebar?.getBoundingClientRect();
    const composerRect = composer?.getBoundingClientRect();
    return {
      viewportHeight: window.innerHeight,
      shellHeight: shell.getBoundingClientRect().height,
      sidebarHeight: sidebarRect?.height,
      sidebarWidth: sidebarRect?.width,
      sidebarTop: sidebarRect?.top,
      sidebarLeft: sidebarRect?.left,
      sidebarPosition: sidebar ? getComputedStyle(sidebar).position : "",
      sidebarOverflowY: sidebar ? getComputedStyle(sidebar).overflowY : "",
      mainOverflowY: mainStage ? getComputedStyle(mainStage).overflowY : "",
      topbarHeight: topbar?.getBoundingClientRect().height,
      chatColumnWidth: chatColumn?.getBoundingClientRect().width,
      composerHeight: composerRect?.height,
      composerBottomOffset: composerRect ? window.innerHeight - composerRect.bottom : undefined
    };
  });

  expect(desktopShell.shellHeight).toBe(desktopShell.viewportHeight);
  expect(desktopShell.sidebarHeight).toBe(desktopShell.viewportHeight);
  expect(desktopShell.sidebarWidth).toBe(276);
  expect(desktopShell.sidebarTop).toBe(0);
  expect(desktopShell.sidebarLeft).toBe(0);
  expect(desktopShell.sidebarPosition).toBe("fixed");
  expect(desktopShell.sidebarOverflowY).toBe("hidden");
  expect(desktopShell.mainOverflowY).toBe("auto");
  expect(desktopShell.topbarHeight).toBe(48);
  expect(desktopShell.chatColumnWidth).toBe(760);
  expect(desktopShell.composerHeight).toBeGreaterThanOrEqual(94);
  expect(desktopShell.composerHeight).toBeLessThanOrEqual(102);
  expect(desktopShell.composerBottomOffset).toBe(16);

  const composer = page.getByLabel("SQL 或巡检报告文本");
  const collapsedHeight = await page.locator(".chat-composer").evaluate((element) => element.getBoundingClientRect().height);
  await composer.fill(Array.from({ length: 8 }, (_, index) => `select ${index + 1}`).join("\n"));
  const expandedHeight = await page.locator(".chat-composer").evaluate((element) => element.getBoundingClientRect().height);
  expect(expandedHeight).toBeGreaterThan(collapsedHeight);
  expect(expandedHeight).toBeLessThanOrEqual(242);
  await composer.fill("");
  await expect.poll(() => page.locator(".chat-composer").evaluate((element) => element.getBoundingClientRect().height)).toBe(collapsedHeight);

  await composer.fill("select 1");
  await page.getByRole("button", { name: "切换到浅色" }).click();
  const sendButton = page.getByRole("button", { name: "提交分析" });
  await sendButton.hover();
  const sendColors = await sendButton.evaluate((element) => {
    const styles = getComputedStyle(element);
    return { background: styles.backgroundColor, foreground: styles.color };
  });
  expect(sendColors.background).not.toBe(sendColors.foreground);
});

test("workspace renders concise validated advice inline in the conversation", async ({ page }, testInfo) => {
  const pastedReport = [
    "SQL ID: B05FC9141039983E7E33ECD3A563E37D",
    "SQL: select * from orders where status = 'PAID' order by created_at desc",
    "执行次数: 21.88",
    "CPU占比: 41.7%",
    "平均耗时: 2008ms",
    "根因: 平均返回行数仅1行。"
  ].join("\n");
  const conversationTitles = [
    "订单慢 SQL",
    "优化 SQL 全表扫描与嵌套循环",
    "检查分页排序访问路径",
    "分析索引回表成本",
    "Oracle 子查询改写",
    "核对分区裁剪条件",
    "处理函数包列问题",
    "复合索引前缀评估",
    "检查隐式类型转换",
    "慢查询报告复核",
    "更新语句安全检查",
    "聚合临时表分析"
  ];
  await page.route("**/api/auth/me", async (route) => {
    await route.fulfill({
      json: { success: true, data: { id: 1, username: "admin", displayName: "Admin", role: "ADMIN" } }
    });
  });
  await page.route("**/api/conversations/page**", async (route) => {
    await route.fulfill({
      json: {
        success: true,
        data: {
          items: conversationTitles.map((title, index) => ({
            id: index + 1,
            userId: 1,
            title,
            createdAt: "2026-07-18T00:00:00Z",
            updatedAt: `2026-07-18T00:${String(index).padStart(2, "0")}:00Z`
          })),
          hasMore: false
        }
      }
    });
  });
  const inlineTask = {
    id: 7,
    userId: 1,
    conversationId: 1,
    dbDialect: "OceanBase MySQL",
    originalSql: "select * from orders where status = 'PAID' order by created_at desc",
    runtimeMetricsText: "执行次数: 21.88\nCPU 占比: 41.7%\n平均耗时: 2008ms\n平均返回行数（报告结论内识别，待核验）: 1 行",
    tableStatsText: "orders: 2294867 行",
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
            body: "1. 先核对调用方实际使用的返回列。\n2. 若调用方不依赖全部列，在测试环境验证缩小投影列后的结果集和执行计划差异。",
            evidenceRefs: ["E_EXPLAIN"]
          },
          {
            kind: "VALIDATION",
            title: "验证标准",
            body: "- 对比改动前后的 EXPLAIN。\n- 确认排序访问路径和返回结果保持一致。\n- 用相同绑定变量比较扫描行数与响应时间。",
            evidenceRefs: ["E_EXPLAIN"]
          },
          {
            kind: "CAUTION",
            title: "上线前注意",
            body: "- 不要在缺少现有索引定义时直接创建重复索引。\n- 先在影子环境验证写入成本和结果集一致性。",
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
  };
  await page.route("**/api/conversations/1/timeline**", async (route) => {
    await route.fulfill({
      json: {
        success: true,
        data: {
          items: [
            { message: { id: -1, conversationId: 1, role: "USER", content: "先按现有信息判断这条查询最可能的瓶颈。", createdAt: "2026-07-17T23:58:00Z" } },
            { message: { id: 0, conversationId: 1, role: "ASSISTANT", content: "上一轮已确认当前 SQL 的筛选和排序结构，但在没有执行计划与现有索引定义前，不能把耗时直接归因于索引缺失。", createdAt: "2026-07-17T23:59:00Z" } },
            { message: { id: 1, conversationId: 1, role: "USER", content: pastedReport, taskId: 7, createdAt: "2026-07-18T00:00:00Z" }, task: inlineTask },
            { message: { id: 2, conversationId: 1, role: "ASSISTANT", content: "当前查询可通过缩小投影列并验证排序访问路径来降低扫描成本。", taskId: 7, createdAt: "2026-07-18T00:00:05Z" }, task: inlineTask }
          ],
          hasMore: false
        }
      }
    });
  });
  await page.route("**/api/tuning/tasks/7", async (route) => {
    await route.fulfill({
      json: {
        success: true,
        data: inlineTask
      }
    });
  });

  await page.goto("/chat");

  await expect(page.locator(".user-sql-message")).toHaveText(pastedReport);
  await expect(page.getByText("已识别证据：")).toBeVisible();
  await expect(page.getByText("执行 21.88 · CPU 41.7% · 平均 2008ms · 返回 1 行 · 表规模约 229 万")).toBeVisible();
  await expect(page.getByText("先确认当前访问路径和索引覆盖情况；在没有计划证据前，不应直接把排序问题归因于索引缺失。")).toBeVisible();
  await expect(page.getByText("问题在哪")).toBeVisible();
  await expect(page.getByText("现有输入可以确认筛选和排序结构。")).toBeVisible();
  await expect(page.getByText("还不能确认实际扫描行数、排序算子或索引命中情况。")).toBeVisible();
  await expect(page.getByText("现在怎么做")).toBeVisible();
  await expect(page.getByText("先核对调用方实际使用的返回列。")).toBeVisible();
  await expect(page.getByText("怎么确认有效")).toBeVisible();
  await expect(page.getByText("对比改动前后的 EXPLAIN。")).toBeVisible();
  await expect(page.getByText("暂时不要做")).toBeVisible();
  await expect(page.getByText("不要在缺少现有索引定义时直接创建重复索引。")).toBeVisible();
  await expect(page.getByText("为什么先验证计划")).toHaveCount(0);
  await expect(page.getByText("验证标准")).toHaveCount(0);
  await expect(page.getByText("上线前注意")).toHaveCount(0);
  await expect(page.getByText("建议改写")).toHaveCount(0);
  await expect(page.getByText("建议验证的索引方案")).toBeVisible();
  await expect(page.getByText(/create index idx_orders_status_created/)).toBeVisible();
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
  await page.route("**/api/conversations/page**", async (route) => {
    await route.fulfill({ json: { success: true, data: { items: [], hasMore: false } } });
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
