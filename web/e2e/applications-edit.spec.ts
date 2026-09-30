import { expect, test } from '@playwright/test'

/**
 * ApplicationsView 编辑流程：版本 → 所属简历定位（ideation #33）。
 *
 * 旧实现按简历数对每个简历的版本列表做并行扇出（N 个请求）；
 * 现在改为单次 `GET /api/resume-versions/{id}`（详情携带 resumeId）后
 * 只重载目标简历的版本列表。本用例断言请求次数与最终选择结果。
 */
const response = (data: unknown) => ({ code: 0, message: 'ok', data, traceId: 'applications-edit-e2e' })
const now = '2026-08-15T10:00:00Z'

const resumes = [
  { id: 1, title: 'Backend resume', currentVersionId: 11, jobDescriptionId: null, createdAt: now, updatedAt: now },
  { id: 2, title: 'Frontend resume', currentVersionId: 22, jobDescriptionId: null, createdAt: now, updatedAt: now },
]
const resumeOneVersions = [
  { id: 11, resumeId: 1, versionNo: 1, sourceType: 'MANUAL', resumeJson: null, optimizationSummary: null, createdAt: now, archivedAt: null, restoredFromVersionId: null, generationContext: null },
]
const resumeTwoVersions = [
  { id: 21, resumeId: 2, versionNo: 1, sourceType: 'MANUAL', resumeJson: null, optimizationSummary: null, createdAt: now, archivedAt: null, restoredFromVersionId: null, generationContext: null },
  { id: 22, resumeId: 2, versionNo: 2, sourceType: 'AI_OPTIMIZED', resumeJson: null, optimizationSummary: null, createdAt: now, archivedAt: null, restoredFromVersionId: null, generationContext: null },
]
const jobs = [{ id: 20, title: 'Backend Engineer', companyName: 'Example Systems', jdText: 'Java and Spring Boot', parsedKeywordsJson: null, parsedAt: null, parsedVersion: null, createdAt: now, updatedAt: now }]
// 关键：投递记录引用的是 resume 2 的「旧版本」21（不是任一简历的当前版本），
// 旧实现必须扇出查询每个简历的版本列表才能定位所属简历。
const application = {
  id: 31, jobDescriptionId: 20, resumeVersionId: 21, status: 'APPLIED', coverLetterText: null,
  emailBodyText: null, openingMessageText: null, feedbackText: null, appliedAt: now,
  nextFollowUpAt: null, version: 1, createdAt: now, updatedAt: now,
}
const stats = {
  total: 1,
  byStatus: [
    { status: 'DRAFT', count: 0, percent: 0 },
    { status: 'APPLIED', count: 1, percent: 100 },
    { status: 'INTERVIEWING', count: 0, percent: 0 },
    { status: 'OFFERED', count: 0, percent: 0 },
    { status: 'REJECTED', count: 0, percent: 0 },
    { status: 'WITHDRAWN', count: 0, percent: 0 },
  ],
  conversionRates: { appliedToInterviewing: 0, interviewingToOffered: null, appliedToOffered: 0 },
  avgStageDurationDays: { applied: 0, interviewing: null, totalToOffer: null },
}

test('editing an application locates its resume version with a single lookup request', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('intelligent-resume.locale', 'en-US'))
  await page.route('**/api/auth/refresh', route => route.fulfill({ json: response({ accessToken: 'edit-token' }) }))
  await page.route('**/api/auth/me', route => route.fulfill({ json: response({ id: 1, username: 'edit-user', email: 'edit@example.com' }) }))
  await page.route('**/api/resumes', route => route.fulfill({ json: response(resumes) }))
  await page.route('**/api/resumes/1/versions**', route => route.fulfill({ json: response(resumeOneVersions) }))
  await page.route('**/api/resumes/2/versions**', route => route.fulfill({ json: response(resumeTwoVersions) }))
  await page.route('**/api/resume-versions/21', route => route.fulfill({
    json: response({ id: 21, resumeId: 2, versionNo: 1, sourceType: 'MANUAL', resumeJson: {}, optimizationSummary: null, generationContext: null, createdAt: now, archivedAt: null, restoredFromVersionId: null }),
  }))
  await page.route('**/api/jobs', route => route.fulfill({ json: response(jobs) }))
  // 注意：Playwright 按注册逆序匹配路由，宽泛的 applications** 先注册，更具体的 /stats 后注册从而优先命中。
  await page.route('**/api/applications**', route => route.fulfill({ json: response([application]) }))
  await page.route('**/api/applications/stats', route => route.fulfill({ json: response(stats) }))

  let lookups = 0
  let versionListsWhileEditing = 0
  let editing = false
  page.on('request', request => {
    const pathname = new URL(request.url()).pathname
    if (pathname === '/api/resume-versions/21') lookups += 1
    if (editing && request.method() === 'GET' && /^\/api\/resumes\/\d+\/versions$/.test(pathname)) {
      versionListsWhileEditing += 1
    }
  })

  await page.goto('/applications')
  await expect(page.locator('.application-ticket')).toHaveCount(1)

  editing = true
  await page.locator('.application-ticket .icon-button').first().click()

  const composer = page.locator('.application-composer')
  await expect(composer).toBeVisible()
  // 定位到 resume 2 并选中被引用的旧版本 21
  await expect(composer.locator('select').nth(0)).toHaveValue('2')
  await expect(composer.locator('select').nth(1)).toHaveValue('21')

  await expect.poll(() => lookups).toBe(1)
  // 只重载目标简历（resume 2）的版本列表：旧实现会扇出 2 次列表请求
  expect(versionListsWhileEditing).toBe(1)
})