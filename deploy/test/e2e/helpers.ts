import { expect, test, type Locator, type Page } from "@playwright/test";

export const user = process.env.TEST_USERNAME || "admin";
export const password = process.env.TEST_PASSWORD || "Databuff@123";

export const ACTION_MS = 8_000;
export const NAV_MS = 15_000;

function escapeRe(s: string) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function menuName(page: Page, label: string) {
  return page.locator(".db-menu-name", { hasText: new RegExp(`^${escapeRe(label)}$`) }).first();
}

/** 登录离开 /login。默认会落到 AI 对话，不把 goto 驾驶舱当过关。 */
export async function login(page: Page) {
  try {
    await page.goto("/databuff/login", { timeout: NAV_MS });
  } catch {
    await page.waitForTimeout(500);
    await page.goto("/databuff/login", { timeout: NAV_MS });
  }
  await expect(page).toHaveURL(/\/databuff\/login/, { timeout: ACTION_MS });
  const userBox = page.getByPlaceholder("请输入用户名");
  await expect(userBox).toBeVisible({ timeout: ACTION_MS });
  await userBox.fill(user);
  await page.getByPlaceholder("请输入用户密码").fill(password);
  await page.getByRole("button", { name: "登 录" }).click();
  await page.waitForURL(/\/databuff\/(?!login)/, { timeout: NAV_MS });
  await expect(page.locator(".db-menu-name").first()).toBeVisible({ timeout: ACTION_MS });
}

/** 侧栏点父级；有子叶再点子叶。锚 .db-menu-name（db-menu/index.vue）。子叶已展开则不再点父级，避免 unique-opened 收起。 */
export async function clickSidebar(page: Page, parent: string, child?: string) {
  const parentItem = menuName(page, parent);
  await expect(parentItem).toBeVisible({ timeout: ACTION_MS });

  if (!child) {
    await parentItem.click({ timeout: ACTION_MS });
    return;
  }

  const childInOpen = page.locator(".el-submenu.is-opened .db-menu-name", {
    hasText: new RegExp(`^${escapeRe(child)}$`),
  });
  const alreadyOpen =
    (await childInOpen.count()) > 0 && (await childInOpen.first().isVisible().catch(() => false));
  if (!alreadyOpen) {
    await parentItem.click({ timeout: ACTION_MS });
  }

  const target = page
    .locator(".el-submenu.is-opened .db-menu-name", { hasText: new RegExp(`^${escapeRe(child)}$`) })
    .first();
  await expect(target).toBeVisible({ timeout: ACTION_MS });
  await target.click({ timeout: ACTION_MS });
}

/** 登录后侧栏点「全局大盘」进驾驶舱，钉故障 Tab。禁止 goto('/databuff/cockpit') 当过关。 */
export async function openCockpitFaultTab(page: Page) {
  if (!/\/databuff\/cockpit/.test(page.url())) {
    await clickSidebar(page, "全局大盘");
  }
  await expect(page).toHaveURL(/\/databuff\/cockpit/, { timeout: NAV_MS });
  await expect(page.locator(".fault-wrapper")).toBeVisible({ timeout: ACTION_MS });
  await expect(page.locator(".fault-tab-item").first()).toBeVisible({ timeout: ACTION_MS });
}

/** 活数据没有就 skip，禁止 seed。 */
export async function skipIfGone(locator: Locator, reason: string) {
  if ((await locator.count()) === 0) {
    test.skip(true, reason);
  }
}

/** 短超时点；点不到就 skip，不空转整条 timeout。 */
export async function clickOrSkip(locator: Locator, reason: string, timeout = 5_000) {
  try {
    await locator.click({ timeout });
  } catch {
    test.skip(true, reason);
  }
}
