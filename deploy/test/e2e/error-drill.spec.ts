import { expect, test } from "@playwright/test";
import { clickOrSkip, clickSidebar, login, skipIfGone } from "./helpers";

test("UI12 error list click InsufficientStockException", async ({ page }) => {
  await login(page);
  await clickSidebar(page, "应用性能", "错误分析");
  await expect(page).toHaveURL(/\/databuff\/appMonitor\/errors/);

  const name = page.locator(".db-blue.cphu", { hasText: "InsufficientStockException" }).first();
  await skipIfGone(name, "错误列表没有 InsufficientStockException，本条诚实缺，禁止造数");
  await clickOrSkip(name, "点不进 InsufficientStockException，诚实缺");
  await expect(page).toHaveURL(/\/databuff\/appMonitor\/errorDetail/);
  await expect(page.locator("body")).toContainText("InsufficientStockException");
  const body = await page.locator("body").innerText();
  expect(/service-a|service-b/.test(body), "详情应能对上 service-a / service-b").toBeTruthy();
});
