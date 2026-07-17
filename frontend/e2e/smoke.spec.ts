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
