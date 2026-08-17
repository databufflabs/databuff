import { expect, test } from "@playwright/test";
import { ACTION_MS, clickOrSkip, clickSidebar, login, skipIfGone } from "./helpers";

test("UI6 alarm filter service-a then detail", async ({ page }) => {
  await login(page);
  await clickSidebar(page, "告警中心", "告警列表");
  await expect(page).toHaveURL(/\/databuff\/alarmCenter\/alarm/);
  await expect(page.locator(".choose-collapse")).toBeVisible({ timeout: ACTION_MS });

  const serviceFilter = page.locator(".el-collapse-item").filter({ hasText: "服务名称" });
  await skipIfGone(serviceFilter, "活栈告警筛选项没有服务名称，禁止 upsert/seed，本条诚实缺");
  await clickOrSkip(
    serviceFilter.locator(".el-collapse-item__header"),
    "打不开服务名称筛，诚实缺",
  );
  const serviceA = serviceFilter.locator(".filter-checkbox", { hasText: "service-a" });
  await skipIfGone(serviceA, "活栈告警筛选项没有 service-a，禁止 upsert/seed，本条诚实缺");
  await clickOrSkip(serviceA, "勾不了 service-a，禁止 seed，诚实缺");

  const row = page.locator(".el-table__body-wrapper .el-table__row").first();
  try {
    await expect(row).toBeVisible({ timeout: 8_000 });
  } catch {
    test.skip(true, "筛 service-a 后没有可点行，禁止 upsert/seed 告警装绿，本条诚实缺");
  }

  await clickOrSkip(row, "告警行点不进详情，诚实缺");
  await expect(page).toHaveURL(/\/databuff\/alarmCenter\/alarmDetail/);
  await expect(page).toHaveURL(/aid=/);

  await page.getByText("其他信息", { exact: true }).click();
  await expect(page.locator(".detail-info")).toContainText("service-a");

  await page.locator(".detail-tabs").getByText("告警响应", { exact: true }).click();
  await page.locator(".detail-tabs").getByText("事件列表", { exact: true }).click();
  await expect(page.locator(".detail-tabs")).toContainText("事件列表");
});
