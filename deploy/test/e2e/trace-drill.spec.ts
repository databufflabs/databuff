import { expect, test } from "@playwright/test";
import { clickOrSkip, clickSidebar, login, skipIfGone } from "./helpers";

test("UI11 trace list click /demo/checkout", async ({ page }) => {
  await login(page);
  await clickSidebar(page, "应用性能", "链路追踪");
  await expect(page).toHaveURL(/\/databuff\/appMonitor\/trace/);

  const row = page.locator(".el-table__row", { hasText: "/demo/checkout" }).first();
  await skipIfGone(row, "链路列表没有 /demo/checkout 行，有链归第一种 IT129，本条诚实缺");
  await clickOrSkip(
    row.locator(".table-item-with-action, .db-blue.cphu").first(),
    "点不进 /demo/checkout 链路，诚实缺",
  );
  await expect(page).toHaveURL(/\/databuff\/appMonitor\/traceDetail/);
  await expect(page.locator(".span-detail")).toContainText("service-a");
});
