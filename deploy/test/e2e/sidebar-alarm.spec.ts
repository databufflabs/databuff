import { expect, test } from "@playwright/test";
import { clickSidebar, login } from "./helpers";

test("UI5 sidebar to alarm list", async ({ page }) => {
  await login(page);
  await clickSidebar(page, "告警中心", "告警列表");
  await expect(page).toHaveURL(/\/databuff\/alarmCenter\/alarm/);
  await expect(page).not.toHaveURL(/\/alarmCenter\/event/);
  await expect(page).not.toHaveURL(/\/alarmCenter\/notice/);
  await expect(page.locator(".choose-collapse")).toBeVisible();
});
