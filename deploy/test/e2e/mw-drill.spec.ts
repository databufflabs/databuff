import { expect, test } from "@playwright/test";
import { clickOrSkip, clickSidebar, login, skipIfGone } from "./helpers";

test("UI13 database list click [mysql]demo_apm", async ({ page }) => {
  await login(page);
  await clickSidebar(page, "应用性能", "数据库");
  await expect(page).toHaveURL(/\/databuff\/appMonitor\/database/);

  const name = page.locator(".service-name-text", { hasText: "[mysql]demo_apm" }).first();
  await skipIfGone(name, "数据库列表没有 [mysql]demo_apm，本条诚实缺，禁止造数");
  await clickOrSkip(name, "点不进 [mysql]demo_apm，诚实缺");
  await expect(page).toHaveURL(/\/databuff\/appMonitor\/database\/detail/);
  await expect(page.locator(".comp-header-title")).toContainText("[mysql]demo_apm");
});
