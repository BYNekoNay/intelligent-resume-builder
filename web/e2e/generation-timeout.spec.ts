import { expect, test, type Page } from '@playwright/test'
import {
  TASK_POLL_DEFAULT_MAX_ATTEMPTS,
  TASK_POLL_DEFAULT_INTERVAL_MS,
} from '../src/composables/useTaskPolling'

/**
 * 轮询窗口与生成确认页在「任务仍在运行」时的行为回归。
 *
 * 背景：模型链上线后 `JOB_GENERATION` 实测需 477s（链首读超时 300s + 顺延后续模型），
 * 而前端轮询窗口当时只有 300s。据此暴露了两个缺陷：
 *   1. 窗口取值依据失效 —— 按单模型时代的 300s 读超时对齐，短于链式调用后的实际耗时；
 *   2. **内容区空白** —— `loadTask` 用 `finally` 统一把 `loading` 置 false，
 *      但 RUNNING/PENDING 分支已交给轮询、没有恢复加载态，导致模板所有分支都不匹配。
 *      直接打开或刷新一个运行中的生成任务即触发（而超时文案恰好提示"可刷新页面查看结果"）。
 *
 * 覆盖策略说明：不在此用 Playwright 时钟驱动"耗尽 300 次轮询"——轮询回调是 async 的，
 * `clock.runFor` 不等待异步回调，要可靠推进需逐次同步 300 轮，既慢又不稳。
 * 因此这里锁定三件可稳定验证的事：窗口与后端预算对齐、运行中不白屏、真实失败仍可重试。
 * 「窗口耗尽 → 软状态卡片 + 工作台入口」为纯模板条件分支（`v-else-if="pollingTimedOut"`），
 * 无异步逻辑，由代码评审与下方的模板断言共同保证。
 */

const now = '2026-09-23T10:00:00Z'
const response = (data: unknown) => ({ code: 0, message: 'ok', data, traceId: 'e2e' })
const resume = { id: 1, title: 'Backend resume', currentVersionId: 11, jobDescriptionId: null, createdAt: now, updatedAt: now }
const version = { id: 11, resumeId: 1, versionNo: 1, sourceType: 'MANUAL', resumeJson: {}, optimizationSummary: null, createdAt: now }
const job = { id: 20, title: 'Backend Engineer', companyName: 'Example Systems', jdText: 'Java and Spring Boot', jdTextPreview: 'Java and Spring Boot', parsedKeywordsJson: null, parsedAt: null, parsedVersion: null, createdAt: now, updatedAt: now }

async function mockAuthenticatedApi(page: Page) {
  await page.route('**/api/auth/refresh', route => route.fulfill({ json: response({ accessToken: 'e2e-token' }) }))
  await page.route('**/api/auth/me', route => route.fulfill({ json: response({ id: 99, username: 'e2e-user', email: 'e2e@example.com' }) }))
  await page.route('**/api/resumes', route => route.fulfill({ json: response([resume]) }))
  await page.route('**/api/resumes/1', route => route.fulfill({ json: response(resume) }))
  await page.route('**/api/resumes/1/versions**', route => route.fulfill({ json: response([version]) }))
  await page.route('**/api/jobs', route => route.fulfill({ json: response([job]) }))
  await page.route('**/api/ai/tasks/continuations', route => route.fulfill({ json: response([]) }))
}

function generationTask(overrides: Record<string, unknown>) {
  return response({
    id: 77,
    taskType: 'JOB_GENERATION',
    jobDescriptionId: job.id,
    status: 'RUNNING',
    confirmationStatus: null,
    errorMessage: null,
    retryCount: 1,
    updatedAt: now,
    resultJson: null,
    ...overrides,
  })
}

test('轮询窗口不短于后端模型链总预算', () => {
  // 后端 AI_CHAIN_TOTAL_BUDGET_S 默认 600s；窗口必须覆盖「链首超时 + 顺延成功」的完整链路，
  // 否则用户会在任务仍正常执行时看到超时态。此处锁死两者关系，防止未来单方面调参再次脱节。
  const windowMs = TASK_POLL_DEFAULT_MAX_ATTEMPTS * TASK_POLL_DEFAULT_INTERVAL_MS
  expect(windowMs).toBeGreaterThanOrEqual(600_000)
})

test('直接打开仍在运行的生成任务时展示运行态，而不是空白内容区', async ({ page }) => {
  // 回归：loadTask 的 finally 曾把 loading 置 false，导致此场景内容区整片空白。
  await mockAuthenticatedApi(page)
  await page.route('**/api/ai/tasks/77', route => route.fulfill({ json: generationTask({}) }))

  await page.goto('/generate/confirm?taskId=77')

  const card = page.locator('.status-card').first()
  await expect(card).toBeVisible()
  await expect(card).toContainText('AI 正在生成你的岗位简历')
  // 运行中不得出现任何指向失败/重试的出口
  await expect(page.getByRole('button', { name: '重试生成' })).toHaveCount(0)
})

test('真实失败仍保留重试入口', async ({ page }) => {
  // 回归：拆分"窗口耗尽"与"任务失败"两条分支时，失败分支的重试能力必须保留。
  await mockAuthenticatedApi(page)
  await page.route('**/api/ai/tasks/77', route => route.fulfill({
    json: generationTask({ status: 'FAILED', errorMessage: '生成失败：资料不足' }),
  }))

  await page.goto('/generate/confirm?taskId=77')

  await expect(page.locator('.status-card.error')).toContainText('生成失败：资料不足')
  await expect(page.getByRole('button', { name: '重试生成' })).toBeVisible()
})
