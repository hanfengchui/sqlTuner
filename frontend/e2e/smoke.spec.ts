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

  if (test.info().project.name === "desktop") {
    await expect(page.getByText("OceanBase SQL")).toBeVisible();
    await expect(page.getByPlaceholder("搜索会话标题")).toBeVisible();
  } else {
    await page.getByLabel("打开导航").click();
    await expect(page.getByRole("dialog", { name: "导航" }).getByText("OceanBase SQL")).toBeVisible();
    await expect(page.getByRole("dialog", { name: "导航" }).getByPlaceholder("搜索会话标题")).toBeVisible();
  }
  await expect(page.getByText("SQL / Inspection Report")).toBeVisible();
  await expect(page.getByLabel("SQL 或巡检报告文本")).toBeVisible();
  await expect(page.getByLabel("图片证据区")).toBeVisible();
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
