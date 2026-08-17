import { expect, test } from "@playwright/test";
import { clickSidebar, login } from "./helpers";

test("UI-top5 service-a instance rank legend has service-a-1", async ({ page }) => {
  await login(page);
  await clickSidebar(page, "应用性能", "服务");
  await expect(page).toHaveURL(/\/databuff\/appMonitor\/service/);

  const name = page.locator(".service-name-text", { hasText: "service-a" }).first();
  await expect(name).toBeVisible({ timeout: 15_000 });
  await name.click();
  await expect(page).toHaveURL(/\/databuff\/appMonitor\/serviceDetail/);
  await expect(page.locator(".comp-header-title")).toContainText("service-a");

  const baseinfo = page.locator(".tabs-nav-item", { hasText: "基础信息" }).first();
  await expect(baseinfo).toBeVisible();
  await baseinfo.click();

  const top5 = page.locator(".custom-radio-segment-item", { hasText: "按实例排行Top5" }).first();
  await expect(top5).toBeVisible();
  await top5.click();
  await expect(page.locator(".chart-group-top")).toBeVisible();
  await expect(page.locator(".chart-group-top")).toContainText("serviceInstance:service-a-1");
});
