import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  timeout: 30_000,
  expect: { timeout: 5_000 },
  use: {
    baseURL: process.env.PLAYWRIGHT_BASE_URL || "http://127.0.0.1:5173",
    trace: "retain-on-failure"
  },
  webServer: {
    command: "npm run dev -- --host 127.0.0.1",
    url: "http://127.0.0.1:5173",
    reuseExistingServer: true,
    timeout: 60_000
  },
  projects: [
    { name: "desktop-1366", use: { ...devices["Desktop Chrome"], viewport: { width: 1366, height: 900 } } },
    { name: "desktop-1920", use: { ...devices["Desktop Chrome"], viewport: { width: 1920, height: 1080 } } }
  ]
});
