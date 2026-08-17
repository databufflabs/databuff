import { expect, test } from "@playwright/test";
import { clickOrSkip, clickSidebar, login, skipIfGone } from "./helpers";

test("UI14 endpoint list click GET /demo/checkout", async ({ page }) => {
  await login(page);
  await clickSidebar(page, "应用性能", "接口分析");
  await expect(page).toHaveURL(/\/databuff\/appMonitor\/serviceAnalysis/);

  const name = page.locator(".db-blue.cphu", { hasText: "/demo/checkout" }).first();
  await skipIfGone(name, "接口列表没有 GET /demo/checkout，本条诚实缺，禁止造数");
  await clickOrSkip(name, "点不进 GET /demo/checkout，诚实缺");
  await expect(page).toHaveURL(/\/databuff\/appMonitor\/resourceDetail/);
  await expect(page.locator(".comp-header-title")).toContainText("/demo/checkout");
  const url = page.url();
  const body = await page.locator("body").innerText();
  expect(/sn=/.test(url) || /service-a/.test(body), "详情服务应对上 service-a").toBeTruthy();
});
