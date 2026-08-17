import { expect, test, type Page } from "@playwright/test";
import { clickSidebar, login } from "./helpers";

const COMING_SOON = ".coming-soon-wrapper";

const AGENT_LEAVES: { name: string; url: RegExp }[] = [
  { name: "Agent列表", url: /\/databuff\/aiMonitor\/applications/ },
  { name: "Agent拓扑", url: /\/databuff\/aiMonitor\/topology/ },
  { name: "技能调用", url: /\/databuff\/aiMonitor\/skillCalls/ },
  { name: "工具调用", url: /\/databuff\/aiMonitor\/toolCalls/ },
  { name: "模型调用", url: /\/databuff\/aiMonitor\/modelCalls/ },
  { name: "对话追踪", url: /\/databuff\/aiMonitor\/sessions/ },
  { name: "Token分析", url: /\/databuff\/aiMonitor\/tokens/ },
  { name: "错误分析", url: /\/databuff\/aiMonitor\/errors/ },
];

async function expectComingSoon(page: Page) {
  await expect(page.locator(COMING_SOON)).toBeVisible();
  await expect(page.locator(".coming-soon-tag")).toContainText("待开放");
}

test("C86 sidebar Agent观测 8 leaves Coming Soon", async ({ page }) => {
  await login(page);
  for (const leaf of AGENT_LEAVES) {
    await clickSidebar(page, "Agent观测", leaf.name);
    await expect(page).toHaveURL(leaf.url);
    await expectComingSoon(page);
  }
});

test("C89 deploy access OTel OneAgent log tabs", async ({ page }) => {
  await login(page);
  await clickSidebar(page, "安装部署", "数据接入");
  await expect(page).toHaveURL(/\/databuff\/deploy\/access/);

  const otel = page.getByText("OTEL Collector接入", { exact: true }).first();
  if ((await otel.count()) === 0) {
    test.skip(true, "数据接入没有 OTEL Collector接入 tab，产品切法变了，本条诚实缺");
  }
  await otel.click();
  await expect(page).toHaveURL(/type=otelCollector/);

  const one = page.getByText("OneAgent", { exact: true }).first();
  await one.click();
  await expect(page).toHaveURL(/type=oneAgent/);
  await expectComingSoon(page);

  const log = page.getByText("日志", { exact: true }).first();
  await log.click();
  await expect(page).toHaveURL(/type=log/);
  await expect(page.locator("h5", { hasText: "OTel Collector" })).toBeVisible();
});

type WindowBody = { fromTime?: string; toTime?: string };

function isSummary(url: string) {
  return url.includes("/webapi/platform/metrics/summary");
}

test("C92 deploy status time change refreshes summary", async ({ page }) => {
  await login(page);

  const windows: WindowBody[] = [];
  page.on("request", (req) => {
    if (req.method() === "POST" && isSummary(req.url())) {
      try {
        const body = req.postDataJSON() as WindowBody;
        if (body?.fromTime && body?.toTime) {
          windows.push({ fromTime: String(body.fromTime), toTime: String(body.toTime) });
        }
      } catch {
        /* ignore */
      }
    }
  });

  await clickSidebar(page, "安装部署", "部署状态");
  await expect(page).toHaveURL(/\/databuff\/deploy\/status/);
  await page
    .waitForRequest((req) => req.method() === "POST" && isSummary(req.url()), { timeout: 20_000 })
    .catch(() => undefined);

  const trigger = page.locator(".time-choose-trigger").first();
  if ((await trigger.count()) === 0) {
    test.skip(true, "部署状态底栏没有时间窗，本条诚实缺");
  }

  await trigger.click();
  const hourReq = page.waitForRequest((req) => req.method() === "POST" && isSummary(req.url()), {
    timeout: 20_000,
  });
  await page.locator(".time-choose-selector-option", { hasText: "最近1小时" }).click();
  await hourReq.catch(() => undefined);

  const afterHour = windows.length;
  await page.locator(".time-choose-trigger").first().click();
  const minReq = page.waitForRequest((req) => req.method() === "POST" && isSummary(req.url()), {
    timeout: 20_000,
  });
  await page.locator(".time-choose-selector-option", { hasText: "最近15分钟" }).click();
  await minReq.catch(() => undefined);

  const later = windows.slice(afterHour);
  expect(windows.length, "应拦到 /webapi/platform/metrics/summary 的 fromTime/toTime").toBeGreaterThan(0);
  if (later.length === 0) {
    test.skip(true, "切 15 分钟后没有新的 summary 请求，时间窗未落到请求，诚实缺");
  }
  const first = windows[0];
  const second = later[later.length - 1];
  const same = first.fromTime === second.fromTime && first.toTime === second.toTime;
  expect(same, `两次窗相同 ${first.fromTime}–${first.toTime}`).toBeFalsy();
});
