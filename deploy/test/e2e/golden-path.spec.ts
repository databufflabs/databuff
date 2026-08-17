import { expect, test } from "@playwright/test";
import { clickOrSkip, clickSidebar, login, openCockpitFaultTab, skipIfGone } from "./helpers";

test("UI1 login to cockpit fault tab", async ({ page }) => {
  await login(page);
  await openCockpitFaultTab(page);
  await expect(page.locator(".fault-tab-item").filter({ hasText: "告警状态" })).toBeVisible();
});

test("UI2 sidebar to service list", async ({ page }) => {
  await login(page);
  await clickSidebar(page, "应用性能", "服务");
  await expect(page).toHaveURL(/\/databuff\/appMonitor\/service/);
});

test("UI3 service list to service-a detail", async ({ page }) => {
  await login(page);
  await clickSidebar(page, "应用性能", "服务");
  await expect(page).toHaveURL(/\/databuff\/appMonitor\/service/);

  const name = page.locator(".service-name-text", { hasText: "service-a" }).first();
  await skipIfGone(name, "服务列表没有 service-a，列表有数归第一种 IT8/C42，本条不装绿");
  await clickOrSkip(name, "点不到 service-a 行，诚实缺，不空转");
  await expect(page).toHaveURL(/\/databuff\/appMonitor\/serviceDetail/);
  await expect(page).toHaveURL(/sid=/);
  await expect(page.locator(".comp-header-title")).toContainText("service-a");
});
