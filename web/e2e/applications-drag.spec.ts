import { expect, test, type Page } from '@playwright/test'

/**
 * ApplicationsView 原生 HTML5 拖拽路径 E2E（O-04）。
 *
 * 覆盖诊断盲区：非法迁移 toast（不发请求）与乐观锁 40901（toast + 重拉列表）。
 * 拖拽通过 dispatchEvent + DataTransfer 模拟，避免 Playwright 鼠标拖拽与
 * HTML5 draggable 的兼容问题。
 */
const response = (data: unknown) => ({ code: 0, message: 'ok', data, traceId: 'applications-drag-e2e' })
const now = '2026-08-15T10:00:00Z'

const resumes = [
  { id: 1, title: 'Backend resume', currentVersionId: 11, jobDescriptionId: null, createdAt: now, updatedAt: now },
  { id: 2, title: 'Frontend resume', currentVersionId: 22, jobDescriptionId: null, createdAt: now, updatedAt: now },
]
const versions = [
  { id: 11, resumeId: 1, versionNo: 1, sourceType: 'MANUAL', resumeJson: null, optimizationSummary: null, createdAt: now, archivedAt: null, restoredFromVersionId: null, generationContext: null },
  { id: 22, resumeId: 2, versionNo: 1, sourceType: 'MANUAL', resumeJson: null, optimizationSummary: null, createdAt: now, archivedAt: null, restoredFromVersionId: null, generationContext: null },
]
const jobs = [{ id: 20, title: 'Backend Engineer', companyName: 'Example Systems', jdText: 'Java and Spring Boot', parsedKeywordsJson: null, parsedAt: null, parsedVersion: null, createdAt: now, updatedAt: now }]

const record = (id: number, status: string, version: number) => ({
  id, jobDescriptionId: 20, resumeVersionId: 11, status, coverLetterText: null, emailBodyText: null,
  openingMessageText: null, feedbackText: null, appliedAt: status === 'APPLIED' ? now : null,
  nextFollowUpAt: null, version, createdAt: now, updatedAt: now,
})

const stats = {
  total: 2,
  byStatus: [
    { status: 'DRAFT', count: 1, percent: 50 },
    { status: 'APPLIED', count: 1, percent: 50 },
    { status: 'INTERVIEWING', count: 0, percent: 0 },
    { status: 'OFFERED', count: 0, percent: 0 },
    { status: 'REJECTED', count: 0, percent: 0 },
    { status: 'WITHDRAWN', count: 0, percent: 0 },
  ],
  conversionRates: { appliedToInterviewing: 0, interviewingToOffered: null, appliedToOffered: 0 },
  avgStageDurationDays: { applied: 0, interviewing: null, totalToOffer: null },
}

async function mockApplicationsPage(page: Page, overrides: { records?: ReturnType<typeof record>[] } = {}) {
  const records = overrides.records ?? [record(1, 'DRAFT', 3), record(2, 'APPLIED', 5)]
  await page.addInitScript(() => localStorage.setItem('intelligent-resume.locale', 'en-US'))
  await page.route('**/api/auth/refresh', route => route.fulfill({ json: response({ accessToken: 'drag-token' }) }))
  await page.route('**/api/auth/me', route => route.fulfill({ json: response({ id: 1, username: 'drag-user', email: 'drag@example.com' }) }))
  await page.route('**/api/resumes', route => route.fulfill({ json: response(resumes) }))
  await page.route('**/api/resumes/1/versions**', route => route.fulfill({ json: response(versions) }))
  await page.route('**/api/resumes/2/versions**', route => route.fulfill({ json: response(versions) }))
  await page.route('**/api/jobs', route => route.fulfill({ json: response(jobs) }))
  // 注意：Playwright 按注册逆序匹配路由，宽泛的 applications** 先注册，
  // 更具体的 /stats 后注册从而优先命中（/api/applications?followUp=ALL 也由宽泛规则覆盖）。
  await page.route('**/api/applications**', route => route.fulfill({ json: response(records) }))
  await page.route('**/api/applications/stats', route => route.fulfill({ json: response(stats) }))
  return records
}

/** 在浏览器上下文里模拟完整 HTML5 拖拽序列（dragstart → dragover → drop → dragend）。 */
async function dragTicketToLane(page: Page, ticketText: string, laneClass: string) {
  await page.evaluate(({ ticketText: text, laneClass: laneSelector }) => {
    const ticket = [...document.querySelectorAll('.application-ticket')]
      .find(el => el.textContent?.includes(text))
    const lane = document.querySelector<HTMLElement>(`.pipeline-lane.${laneSelector}`)
    if (!ticket || !lane) throw new Error('drag target not found')
    const dt = new DataTransfer()
    ticket.dispatchEvent(new DragEvent('dragstart', { bubbles: true, cancelable: true, dataTransfer: dt }))
    lane.dispatchEvent(new DragEvent('dragover', { bubbles: true, cancelable: true, dataTransfer: dt }))
    lane.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: dt }))
    ticket.dispatchEvent(new DragEvent('dragend', { bubbles: true, dataTransfer: dt }))
  }, { ticketText, laneClass })
}

test('illegal drag migration shows a toast and never sends a status request', async ({ page }) => {
  await mockApplicationsPage(page)
  let statusRequests = 0
  page.on('request', request => {
    if (request.url().includes('/api/applications/') && request.url().endsWith('/status')) statusRequests += 1
  })

  await page.goto('/applications')
  await expect(page.locator('.application-ticket')).toHaveCount(2)
  const draftTicket = page.locator('.pipeline-lane.lane-draft .application-ticket').first()
  await expect(draftTicket).toContainText('Backend Engineer')

  // DRAFT → OFFERED 是非法迁移：toast 阻止，不发任何请求、不移列
  await dragTicketToLane(page, 'Backend Engineer', 'lane-offered')

  await expect(page.locator('.toast-item.toast-error')).toContainText('This status change is not allowed.')
  expect(statusRequests).toBe(0)
  await expect(page.locator('.pipeline-lane.lane-draft .application-ticket')).toHaveCount(1)
  await expect(page.locator('.pipeline-lane.lane-offered .application-ticket')).toHaveCount(0)
})

test('legal drag that hits an optimistic-lock conflict toasts and reloads the list', async ({ page }) => {
  let listLoads = 0
  page.on('request', request => {
    const pathname = new URL(request.url()).pathname
    if (request.method() === 'GET' && pathname === '/api/applications') listLoads += 1
  })
  await mockApplicationsPage(page, { records: [record(1, 'DRAFT', 3), record(2, 'APPLIED', 5)] })
  await page.route('**/api/applications/1/status', route => route.fulfill({ status: 409, json: { code: 40901, message: 'conflict' } }))

  await page.goto('/applications')
  await expect(page.locator('.application-ticket')).toHaveCount(2)

  // DRAFT → APPLIED 合法，但后端返回 40901 乐观锁冲突
  await dragTicketToLane(page, 'Backend Engineer', 'lane-applied')

  await expect(page.locator('.toast-item.toast-error')).toContainText('This record was updated by another action. Refresh and try again.')
  // 冲突后重新拉取列表（load 重新执行）
  await expect.poll(() => listLoads).toBeGreaterThanOrEqual(2)
  await expect(page.locator('.application-ticket')).toHaveCount(2)
})
