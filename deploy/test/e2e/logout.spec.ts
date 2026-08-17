import { expect, test } from "@playwright/test";
import { ACTION_MS, login } from "./helpers";

test("UI4 logout back to login then gated", async ({ page }) => {
  await login(page);
  // 对话页也有 .user-avatar，必须点壳上的头像（user-info.vue）。
  await page.locator(".user-info-cont .user-avatar").click({ timeout: ACTION_MS });
  const logout = page.getByText("退出登录", { exact: true });
  await expect(logout).toBeVisible({ timeout: ACTION_MS });
  await logout.click({ timeout: ACTION_MS });
  await expect(page).toHaveURL(/\/databuff\/login/, { timeout: 15_000 });

  await page.goto("/databuff/appMonitor/service");
  await expect(page).toHaveURL(/\/databuff\/login/);
});
