import { defineConfig } from "@playwright/test";

const baseURL = process.env.TEST_BASE_URL || "http://127.0.0.1:27403";

export default defineConfig({
  testDir: ".",
  timeout: 60_000,
  expect: { timeout: 8_000 },
  fullyParallel: false,
  workers: 1,
  // 禁止 maxFailures=1：有人仍整组跑时，一条红不能挡住后面的条。
  reporter: [
    ["list"],
    ["json", { outputFile: "test-results/e2e-last.json" }],
  ],
  use: {
    baseURL,
    viewport: { width: 1440, height: 900 },
    ignoreHTTPSErrors: true,
    actionTimeout: 8_000,
    navigationTimeout: 15_000,
  },
});
