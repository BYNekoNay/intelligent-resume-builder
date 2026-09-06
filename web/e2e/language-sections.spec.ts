import { expect, test, type Page } from '@playwright/test'

/**
 * P3-3 回归：`sectionLabels` 已从静态对象改为 computed（由 `t()` 驱动），
 * 用于修复「切换语言后章节标签不更新」的响应式缺陷。
 *
 * 本 spec 在三个真实渲染点上断言：切换语言后，章节标签（而非页面标题/按钮）
 * 会即时更新且**不需要刷新页面**：
 *  1. ResumeDetailView    /resumes/1         章节筛选下拉 option + 关联素材 asset-tag
 *  2. InterviewAssetsView /interview-assets  章节过滤下拉 option + asset-tag（含编辑表单 checkbox 增强）
 *  3. CompareVersionsView /resumes/1/compare 版本对比章节标题 <h2>
 *
 * 全部数据走路由 mock（zh-CN 为默认 locale，首次加载不写 localStorage）。
 */
const now = '2026-08-01T09:00:00Z'
const response = (data: unknown) => ({ code: 0, message: 'ok', data, traceId: 'lang-sections-e2e' })

// resumeEditor 命名空间内双语章节标签（zh → en）
const ZH = { work: '工作经历', projects: '项目经历', education: '教育经历', skills: '技能' }
const EN = { work: 'Work Experience', projects: 'Projects', education: 'Education', skills: 'Skills' }

const linkedAsset = {
  id: 1,
  interviewRecordId: 51,
  questionText: 'Tell me about a technical project.',
  originalAnswerText: 'Led the ordering platform refactor and cut p99 latency.',
  suggestedAnswerText: null,
  feedbackJson: null,
  createdAt: now,
  updatedAt: now,
  sectionKeys: ['work', 'projects'],
  materialIds: [],
}

async function mockAuthenticatedApi(page: Page) {
  await page.route('**/api/auth/refresh', route => route.fulfill({ json: response({ accessToken: 'lang-e2e-token' }) }))
  await page.route('**/api/auth/me', route => route.fulfill({ json: response({ id: 99, username: 'e2e-user', email: 'e2e@example.com' }) }))
}

async function mockResumeDetail(page: Page) {
  const resume = { id: 1, title: 'Backend resume', currentVersionId: 11, jobDescriptionId: null, createdAt: now, updatedAt: now }
  const versions = [
    { id: 11, resumeId: 1, versionNo: 1, sourceType: 'MANUAL', templateCode: 'classic', optimizationSummary: null, createdAt: now, archivedAt: null, restoredFromVersionId: null },
  ]
  await mockAuthenticatedApi(page)
  await page.route('**/api/resumes/1', route => route.fulfill({ json: response(resume) }))
  await page.route('**/api/resumes/1/versions**', route => route.fulfill({ json: response(versions) }))
  await page.route('**/api/interview-answer-assets**', route => route.fulfill({ json: response([linkedAsset]) }))
  await page.route('**/api/career-materials*', route => route.fulfill({ json: response([]) }))
}

async function mockInterviewAssets(page: Page) {
  await mockAuthenticatedApi(page)
  await page.route('**/api/jobs', route => route.fulfill({ json: response([]) }))
  await page.route('**/api/interview-answer-assets**', route => route.fulfill({ json: response([linkedAsset]) }))
  await page.route('**/api/career-materials*', route => route.fulfill({ json: response([]) }))
}

async function mockCompareVersions(page: Page) {
  const resume = { id: 1, title: 'Backend resume', currentVersionId: 10, jobDescriptionId: null, createdAt: now, updatedAt: now }
  const versions = [
    { id: 10, resumeId: 1, versionNo: 1, sourceType: 'MANUAL', resumeJson: null, optimizationSummary: null, createdAt: now, archivedAt: null, restoredFromVersionId: null, generationContext: null },
    { id: 11, resumeId: 1, versionNo: 2, sourceType: 'JD_CUSTOMIZED', resumeJson: null, optimizationSummary: 'Tailored for backend role', createdAt: now, archivedAt: null, restoredFromVersionId: null, generationContext: null },
  ]
  const baseJson = {
    basics: { name: 'Base Candidate', email: 'base@example.com' },
    work: [
      { id: 'w1', company: 'Base Systems', position: 'Engineer', description: 'Built Java services.' },
    ],
    projects: [{ id: 'p1', name: 'Order Platform', role: 'Lead' }],
  }
  const compareJson = {
    basics: { name: 'Compare Candidate', email: 'base@example.com' },
    work: [
      { id: 'w1', company: 'Base Systems', position: 'Senior Engineer', description: 'Built Java services.' },
    ],
    projects: [
      { id: 'p1', name: 'Order Platform', role: 'Lead' },
      { id: 'p2', name: 'Data Pipeline', role: 'Architect' },
    ],
  }
  await mockAuthenticatedApi(page)
  await page.route('**/api/resumes/1', route => route.fulfill({ json: response(resume) }))
  await page.route('**/api/resumes/1/versions**', route => route.fulfill({ json: response(versions) }))
  await page.route('**/api/resume-versions/10', route => route.fulfill({ json: response({ ...versions[0], resumeJson: baseJson }) }))
  await page.route('**/api/resume-versions/11', route => route.fulfill({ json: response({ ...versions[1], resumeJson: compareJson }) }))
}

async function switchTo(page: Page, buttonName: '中文' | 'EN') {
  await page.getByRole('button', { name: buttonName, exact: true }).click()
}

test('ResumeDetailView section filter options and asset tags switch language without reload', async ({ page }) => {
  await mockResumeDetail(page)

  await page.goto('/resumes/1')
  const filter = page.locator('.assets-section-filter')
  await expect(filter.locator('option[value="work"]')).toHaveText(ZH.work)
  await expect(filter.locator('option[value="projects"]')).toHaveText(ZH.projects)
  // 关联素材 asset-tag 也来自 sectionLabels computed
  const detailTags = page.locator('.related-assets .asset-tags .asset-tag')
  await expect(detailTags).toHaveText([ZH.work, ZH.projects])

  // 不 reload：点击 EN 后同一下拉 option / asset-tag 即时变为英文
  await switchTo(page, 'EN')
  await expect(filter.locator('option[value="work"]')).toHaveText(EN.work)
  await expect(filter.locator('option[value="projects"]')).toHaveText(EN.projects)
  await expect(detailTags).toHaveText([EN.work, EN.projects])

  // 切回中文后恢复
  await switchTo(page, '中文')
  await expect(filter.locator('option[value="work"]')).toHaveText(ZH.work)
  await expect(filter.locator('option[value="projects"]')).toHaveText(ZH.projects)
  await expect(detailTags).toHaveText([ZH.work, ZH.projects])
})

test('InterviewAssetsView section filter, asset tags, and composer section checkboxes switch language without reload', async ({ page }) => {
  await mockInterviewAssets(page)

  await page.goto('/interview-assets')
  const filter = page.locator('.asset-filters')
  await expect(filter.locator('option[value="work"]')).toHaveText(ZH.work)
  await expect(filter.locator('option[value="projects"]')).toHaveText(ZH.projects)
  const cardTags = page.locator('.asset-card .asset-tags .asset-tag')
  await expect(cardTags).toHaveText([ZH.work, ZH.projects])
  // 增强覆盖：编辑表单 checkbox 行的 SECTION_KEYS 标签同样由 computed 驱动
  const composer = page.locator('.asset-composer')
  await expect(composer.locator('.section-option').filter({ hasText: ZH.work })).toBeVisible()
  await expect(composer.locator('.section-option').filter({ hasText: ZH.education })).toBeVisible()

  await switchTo(page, 'EN')
  await expect(filter.locator('option[value="work"]')).toHaveText(EN.work)
  await expect(filter.locator('option[value="projects"]')).toHaveText(EN.projects)
  await expect(cardTags).toHaveText([EN.work, EN.projects])
  await expect(composer.locator('.section-option').filter({ hasText: EN.work })).toBeVisible()
  await expect(composer.locator('.section-option').filter({ hasText: EN.education })).toBeVisible()

  await switchTo(page, '中文')
  await expect(filter.locator('option[value="work"]')).toHaveText(ZH.work)
  await expect(filter.locator('option[value="projects"]')).toHaveText(ZH.projects)
  await expect(cardTags).toHaveText([ZH.work, ZH.projects])
  await expect(composer.locator('.section-option').filter({ hasText: ZH.work })).toBeVisible()
})

test('CompareVersionsView section diff headings switch language without reload', async ({ page }) => {
  await mockCompareVersions(page)

  await page.goto('/resumes/1/compare')
  const sectionTitles = page.locator('.compare-section-header h2')
  await expect(sectionTitles.filter({ hasText: ZH.work })).toBeVisible()
  await expect(sectionTitles.filter({ hasText: ZH.projects })).toBeVisible()

  await switchTo(page, 'EN')
  await expect(sectionTitles.filter({ hasText: EN.work })).toBeVisible()
  await expect(sectionTitles.filter({ hasText: EN.projects })).toBeVisible()
  await expect(sectionTitles.filter({ hasText: ZH.work })).toHaveCount(0)
  await expect(sectionTitles.filter({ hasText: ZH.projects })).toHaveCount(0)

  await switchTo(page, '中文')
  await expect(sectionTitles.filter({ hasText: ZH.work })).toBeVisible()
  await expect(sectionTitles.filter({ hasText: ZH.projects })).toBeVisible()
  await expect(sectionTitles.filter({ hasText: EN.work })).toHaveCount(0)
  await expect(sectionTitles.filter({ hasText: EN.projects })).toHaveCount(0)
})
