import { expect, test, type Page } from '@playwright/test'

/**
 * 归档版本不可消费的「可操作指引」在三个真实入口页面可见性 E2E（第五十一批）。
 *
 * 覆盖诊断盲区：服务端对归档消费返回专属码 `40902`，但**只有走 `resolveApiError`
 * 的视图**才会把码映射成文案。此前导出（ResumeDetailView）、投递（ApplicationsView）、
 * 沟通（CommunicationView）三处都用固定 i18n 兜底串，把 40902 吞成「导出失败/保存失败/
 * 生成失败」——用户看不到「先恢复该版本」这一唯一正确动作。
 *
 * 本组用例逐个入口断言：40902 → 出现「Restore it in Version history」指引；
 * 并且**不得**只显示各页原兜底串（避免文案被吞回旧行为）。
 */
const response = (data: unknown) => ({ code: 0, message: 'ok', data, traceId: 'archive-guidance-e2e' })
const archiveConflict = { code: 40902, message: '该简历版本已归档，请先恢复后再继续', data: null, traceId: 'archive-guidance-e2e' }
const ARCHIVE_HINT = 'Restore it in Version history'
const now = '2026-08-16T10:00:00Z'

const resume = { id: 1, title: 'Backend resume', currentVersionId: 11, jobDescriptionId: null, createdAt: now, updatedAt: now }
const version = { id: 11, resumeId: 1, versionNo: 1, sourceType: 'MANUAL', resumeJson: null, optimizationSummary: null, createdAt: now, archivedAt: null, restoredFromVersionId: null, generationContext: null }
const job = { id: 20, title: 'Backend Engineer', companyName: 'Example Systems', jdText: 'Java and Spring Boot', parsedKeywordsJson: null, parsedAt: null, parsedVersion: null, createdAt: now, updatedAt: now }

async function mockShell(page: Page) {
  await page.addInitScript(() => localStorage.setItem('intelligent-resume.locale', 'en-US'))
  await page.route('**/api/auth/refresh', route => route.fulfill({ json: response({ accessToken: 'archive-token' }) }))
  await page.route('**/api/auth/me', route => route.fulfill({ json: response({ id: 1, username: 'archive-user', email: 'archive@example.com' }) }))
  await page.route('**/api/resumes', route => route.fulfill({ json: response([resume]) }))
  await page.route('**/api/resumes/1/versions**', route => route.fulfill({ json: response([version]) }))
  await page.route('**/api/jobs', route => route.fulfill({ json: response([job]) }))
  await page.route('**/api/communications/templates**', route => route.fulfill({ json: response([]) }))
}

test('communication draft generation surfaces the archived-version guidance', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await mockShell(page)
  await page.route('**/api/communications/generate', route =>
    route.fulfill({ status: 409, json: archiveConflict }))

  await page.goto('/communications')
  const versionSelect = page.locator('select').nth(1)
  await expect(versionSelect).toBeEnabled()
  await versionSelect.selectOption('11')
  await page.locator('select').nth(2).selectOption('20')
  await page.locator('select').nth(3).selectOption('EMAIL')
  await page.locator('form.compact-form .generation-actions button').nth(1).click()

  const status = page.locator('.communication-status')
  await expect(status).toBeVisible({ timeout: 10_000 })
  await expect(status).toContainText(ARCHIVE_HINT)
})

test('application creation surfaces the archived-version guidance', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await mockShell(page)
  // 沟通页「用于投递」写入的草稿会预填并展开投递表单（与真实用户路径一致）
  await page.addInitScript(() => {
    sessionStorage.setItem('application-draft', JSON.stringify({
      resumeVersionId: 11, jobDescriptionId: 20, type: 'EMAIL', text: 'Hello Example Systems.',
    }))
  })
  await page.route('**/api/applications/stats', route =>
    route.fulfill({ json: response({
      total: 0,
      byStatus: ['DRAFT', 'APPLIED', 'INTERVIEWING', 'OFFERED', 'REJECTED', 'WITHDRAWN']
        .map(status => ({ status, count: 0, percent: null })),
      conversionRates: { appliedToInterviewing: null, interviewingToOffered: null, appliedToOffered: null },
      avgStageDurationDays: { applied: null, interviewing: null, totalToOffer: null },
    }) }))
  await page.route('**/api/applications', route => {
    if (route.request().method() === 'POST') {
      return route.fulfill({ status: 409, json: archiveConflict })
    }
    return route.fulfill({ json: response([]) })
  })

  await page.goto('/applications')
  const composer = page.locator('form.application-composer')
  await expect(composer).toBeVisible({ timeout: 10_000 })
  await composer.locator('.job-actions .btn-primary').click()

  await expect(page.locator('.form-error[role="alert"]')).toContainText(ARCHIVE_HINT, { timeout: 10_000 })
})

test('pdf export from the resume history surfaces the archived-version guidance', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await mockShell(page)
  await page.route('**/api/resumes/1', route => route.fulfill({ json: response(resume) }))
  await page.route('**/api/interview-answer-assets**', route => route.fulfill({ json: response([]) }))
  await page.route('**/api/career-materials**', route => route.fulfill({ json: response([]) }))
  await page.route('**/api/exports/pdf', route => route.fulfill({ status: 409, json: archiveConflict }))

  await page.goto('/resumes/1')
  await page.locator('.version-list').getByRole('button', { name: 'Export PDF' }).first().click()

  await expect(page.locator('.form-error[role="alert"]')).toContainText(ARCHIVE_HINT, { timeout: 10_000 })
})