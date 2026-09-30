import { expect, test, type Page } from '@playwright/test'

// 第四十二批：退出登录 / 删除账号后，浏览器本地不得残留该账号的简历数据。
// 修复前编辑器草稿（完整简历 JSON，含姓名/联系方式/工作经历）留在
// localStorage['intelligent-resume.editor-draft.<userId>.<resumeId>']，登出与删号都不清理——
// 共享设备上换人使用即可读到上一账号的简历原文。

const now = '2026-07-22T10:00:00Z'
const response = (data: unknown) => ({ code: 0, message: 'ok', data, traceId: 'e2e' })
const resume = { id: 1, title: 'Backend resume', currentVersionId: 11, jobDescriptionId: null, createdAt: now, updatedAt: now }
const version = { id: 11, resumeId: 1, versionNo: 1, sourceType: 'MANUAL', resumeJson: {}, optimizationSummary: null, createdAt: now }

const DRAFT_KEY = 'intelligent-resume.editor-draft.99.1'
const TASK_KEY = 'intelligent-resume.active-ai-task.99'

async function mockAuthenticatedApi(page: Page) {
  await page.route('**/api/auth/refresh', route => route.fulfill({ json: response({ accessToken: 'e2e-token' }) }))
  await page.route('**/api/auth/me', route => route.fulfill({ json: response({ id: 99, username: 'e2e-user', email: 'e2e@example.com' }) }))
  await page.route('**/api/auth/logout', route => route.fulfill({ json: response(null) }))
  await page.route('**/api/resumes', route => route.fulfill({ json: response([resume]) }))
  await page.route('**/api/resumes/1', route => route.fulfill({ json: response(resume) }))
  await page.route('**/api/resumes/1/versions**', route => route.fulfill({ json: response([version]) }))
  await page.route('**/api/resume-versions/11', route => route.fulfill({
    json: response({
      ...version,
      resumeJson: { basics: { name: 'Alice', title: '', summary: '' }, work: [], education: [], skills: [], template: { code: 'classic' } },
    }),
  }))
  await page.route('**/api/jobs', route => route.fulfill({ json: response([]) }))
  await page.route('**/api/career-materials/search*', route => route.fulfill({ json: response({ items: [], page: 0, size: 25, totalElements: 0, totalPages: 0, typeCounts: {} }) }))
  await page.route('**/api/career-materials*', route => route.fulfill({ json: response([]) }))
  await page.route('**/api/personal-profile*', route => route.fulfill({ json: response({ fullName: '', email: '', phone: '', location: '', website: '', profileSummary: '' }) }))
  await page.route('**/api/applications', route => route.fulfill({ json: response([]) }))
  await page.route('**/api/interview-answer-assets**', route => route.fulfill({ json: response([]) }))
  await page.route('**/api/ai/consent', route => route.fulfill({ json: response(null) }))
}

/** 打开编辑器改一处内容，等防抖把草稿写进 localStorage，并留下标签页级用户数据。 */
async function seedBrowserLocalData(page: Page) {
  await page.goto('/resumes/1/edit')
  await expect(page.locator('.studio-grid')).toBeVisible()
  await page.getByLabel('姓名').fill('Alice Chen')
  await expect.poll(() => page.evaluate(key => localStorage.getItem(key), DRAFT_KEY)).not.toBeNull()
  await page.evaluate(key => localStorage.setItem(key, '71'), TASK_KEY)
  await page.evaluate(() => {
    // 与导入页 / 文案页留下的键一致（键名为应用契约，见 utils/localUserData.ts 的清单）
    sessionStorage.setItem('resume-import-text', '从 PDF 解析出的简历原文')
    sessionStorage.setItem('application-draft', '{"text":"沟通文案草稿"}')
  })
  const stored = await page.evaluate(() => [
    localStorage.getItem('intelligent-resume.editor-draft.99.1'),
    localStorage.getItem('intelligent-resume.active-ai-task.99'),
    sessionStorage.getItem('resume-import-text'),
    sessionStorage.getItem('application-draft'),
  ])
  expect(stored.every(value => value !== null)).toBe(true)
  expect(stored[0]).toContain('Alice Chen')
}

/** 登出与删号都必须清掉上面的全部键。 */
async function expectBrowserLocalDataCleared(page: Page) {
  await expect.poll(() => page.evaluate(key => localStorage.getItem(key), DRAFT_KEY)).toBeNull()
  await expect.poll(() => page.evaluate(key => localStorage.getItem(key), TASK_KEY)).toBeNull()
  await expect.poll(() => page.evaluate(key => sessionStorage.getItem(key), 'resume-import-text')).toBeNull()
  await expect.poll(() => page.evaluate(key => sessionStorage.getItem(key), 'application-draft')).toBeNull()
}

test('退出登录后清理浏览器残留的账号数据（简历草稿、待恢复任务、导入原文）', async ({ page }) => {
  await mockAuthenticatedApi(page)
  await seedBrowserLocalData(page)

  // 有未保存改动时编辑器会拦截路由离开并弹 confirm（Playwright 默认自动取消），
  // 这里接受「离开」，与真实用户点击确认等价。
  page.on('dialog', dialog => dialog.accept())
  await page.getByRole('button', { name: '退出登录' }).click()
  await expect(page).toHaveURL(/127\.0\.0\.1:4173\/$/)
  await expectBrowserLocalDataCleared(page)
})

test('删除账号后清理浏览器残留的账号数据', async ({ page }) => {
  await mockAuthenticatedApi(page)
  await page.route('**/api/auth/me', route => {
    if (route.request().method() === 'DELETE') return route.fulfill({ json: response(null) })
    return route.fulfill({ json: response({ id: 99, username: 'e2e-user', email: 'e2e@example.com' }) })
  })
  await seedBrowserLocalData(page)

  await page.goto('/account')
  await page.getByRole('button', { name: '删除账号' }).click()
  const dialog = page.getByRole('dialog', { name: '删除账号' })
  await dialog.getByLabel('输入用户名以确认').fill('e2e-user')
  await dialog.getByRole('button', { name: '确认删除账号' }).click()
  await expect(page).toHaveURL(/\/login$/)
  await expectBrowserLocalDataCleared(page)
})
