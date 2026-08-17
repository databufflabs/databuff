import { expect, test } from "@playwright/test";
import { clickOrSkip, login, openCockpitFaultTab, skipIfGone } from "./helpers";

test("UI7 cockpit click service-a to detail", async ({ page }) => {
  await login(page);
  await openCockpitFaultTab(page);

  const name = page.locator(".apm-alarm-item .ell", { hasText: "service-a" }).first();
  await skipIfGone(name, "故障 Tab 没有 service-a 行，活栈空则诚实缺，禁止造数");
  await clickOrSkip(name, "点不到驾驶舱 service-a，诚实缺");
  await expect(page).toHaveURL(/\/databuff\/appMonitor\/serviceDetail/);
  await expect(page).toHaveURL(/sid=/);
  await expect(page).toHaveURL(/sn=/);
  await expect(page.locator(".comp-header-title")).toContainText("service-a");
});

test("UI8 cockpit click non-zero count", async ({ page }) => {
  await login(page);
  await openCockpitFaultTab(page);

  // fault/index.vue：有值才渲染 span.cp.count；0 是 span.count 无 cp。
  const count = page.locator(".apm-alarm-item span.cp.count").first();
  await skipIfGone(count, "故障 Tab 没有非 0 可点计数，活栈全 0 则诚实缺，禁止造数");
  await clickOrSkip(count, "点不到非 0 计数，诚实缺");

  const url = page.url();
  const toAlarm = /\/databuff\/alarmCenter\/alarm/.test(url) && /serviceId=/.test(url);
  const toErrors = /\/databuff\/appMonitor\/errors/.test(url);
  expect(toAlarm || toErrors, `期望告警?serviceId= 或错误分析，实际 ${url}`).toBeTruthy();
  if (toErrors) {
    await expect(page).toHaveURL(/sn=|sid=/);
  }
});
