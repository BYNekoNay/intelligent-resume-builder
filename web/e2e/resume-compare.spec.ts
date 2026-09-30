import { expect, test, type Page } from '@playwright/test'

/**
 * CompareVersionsView（/resumes/:id/compare）路由级 E2E。
 *
 * 覆盖 O-04 要求的版本对比盲区：diff 摘要、字段级高亮、仅看有变化章节过滤。
 * 全部数据走可控路由 mock，不依赖后端。
 */
const response = (data: unknown) => ({ code: 0, message: 'ok', data, traceId: 'compare-e2e' })

const resume = { id: 1, title: 'Backend resume', currentVersionId: 10, jobDescriptionId: null, createdAt: '2026-08-01T09:00:00Z', updatedAt: '2026-08-10T09:00:00Z' }

const versions = [
  { id: 10, resumeId: 1, versionNo: 1, sourceType: 'MANUAL', resumeJson: null, optimizationSummary: null, createdAt: '2026-08-01T09:00:00Z', archivedAt: null, restoredFromVersionId: null, generationContext: null },
  { id: 11, resumeId: 1, versionNo: 2, sourceType: 'JD_CUSTOMIZED', resumeJson: null, optimizationSummary: 'Tailored for backend role', createdAt: '2026-08-10T09:00:00Z', archivedAt: null, restoredFromVersionId: null, generationContext: null },
]

const baseJson = {
  basics: { name: 'Base Candidate', email: 'base@example.com' },
  work: [
    { id: 'w1', company: 'Base Systems', position: 'Engineer', description: 'Built Java services.' },
    { id: 'w2', company: 'Legacy Systems', position: 'Intern', description: 'Old role.' },
  ],
  skills: [
    { id: 's1', name: 'Java', level: 'Advanced' },
    { id: 's2', name: 'MySQL', level: 'Intermediate' },
  ],
}

const compareJson = {
  basics: { name: 'Compare Candidate', email: 'base@example.com' },
  work: [
    { id: 'w1', company: 'Base Systems', position: 'Senior Engineer', description: 'Built Java services.' },
    { id: 'w2', company: 'Legacy Systems', position: 'Intern', description: 'Old role.' },
    { id: 'w3', company: 'New Systems', position: 'Architect', description: 'New role.' },
  ],
  skills: [
    { id: 's1', name: 'Java', level: 'Advanced' },
    { id: 's2', name: 'MySQL', level: 'Intermediate' },
    { id: 's3', name: 'Redis', level: 'Beginner' },
  ],
}

async function mockComparePage(page: Page) {
  await page.addInitScript(() => localStorage.setItem('intelligent-resume.locale', 'en-US'))
  await page.route('**/api/auth/refresh', route => route.fulfill({ json: response({ accessToken: 'compare-token' }) }))
  await page.route('**/api/auth/me', route => route.fulfill({ json: response({ id: 1, username: 'compare-user', email: 'compare@example.com' }) }))
  await page.route('**/api/resumes/1', route => route.fulfill({ json: response(resume) }))
  await page.route('**/api/resumes/1/versions**', route => route.fulfill({ json: response(versions) }))
  await page.route('**/api/resume-versions/10', route => route.fulfill({ json: response({ ...versions[0], resumeJson: baseJson }) }))
  await page.route('**/api/resume-versions/11', route => route.fulfill({ json: response({ ...versions[1], resumeJson: compareJson }) }))
}

test('shows the diff summary, field-level highlights, and the changed-only filter', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await mockComparePage(page)

  await page.goto('/resumes/1/compare')

  await expect(page.getByRole('heading', { name: 'Compare Resume Versions' })).toBeVisible()
  await expect(page.getByText(/Backend resume/)).toBeVisible()

  // diff 摘要：3 个有变化章节、2 个新增条目、0 删除、2 个修改
  const summary = page.locator('.diff-summary')
  await expect(summary).toContainText('Changed sections')
  await expect(summary.locator('.diff-added strong')).toHaveText('2')
  await expect(summary.locator('.diff-removed strong')).toHaveText('0')
  await expect(summary.locator('.diff-modified strong')).toHaveText('2')

  // 变化章节默认展开：basics 对象章节 name 字段级高亮（MODIFIED）
  const basicsRow = page.locator('.field-diff-row.field-modified').filter({ hasText: 'Name' })
  await expect(basicsRow).toBeVisible()
  await expect(basicsRow.locator('.field-side.base')).toContainText('Base Candidate')
  await expect(basicsRow.locator('.field-side.compare')).toContainText('Compare Candidate')

  // work 数组章节：w1 修改（position 字段高亮）、w3 新增（entry-added）
  const changedEntry = page.locator('.entry-diff.entry-changed').filter({ hasText: 'Base Systems' })
  await expect(changedEntry).toBeVisible()
  await expect(changedEntry.locator('.entry-field.field-modified').filter({ hasText: 'Senior Engineer' })).toBeVisible()
  await expect(page.locator('.entry-diff.entry-added').filter({ hasText: 'New Systems' })).toBeVisible()
  await expect(page.locator('.entry-diff.entry-added').filter({ hasText: 'Redis' })).toBeVisible()

  // 未变化章节（education 等）默认可见
  await expect(page.locator('.section-badge.unchanged').first()).toBeVisible()

  // 仅看有变化章节过滤：勾选后只保留 3 个变化章节
  await page.getByRole('checkbox').check()
  await expect(page.locator('.compare-section')).toHaveCount(3)
  await expect(page.locator('.section-badge.unchanged')).toHaveCount(0)
  await expect(page.locator('.compare-section').filter({ hasText: 'Personal Info' })).toBeVisible()

  // 取消勾选后恢复全部 14 个章节
  await page.getByRole('checkbox').uncheck()
  await expect(page.locator('.compare-section')).toHaveCount(14)
})

/**
 * #63 回归：每次版本选择只发一次 diff 请求；连续切换时旧响应不得覆盖最新选择。
 */
test('deduplicates version fetches per selection and ignores a stale diff response', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.addInitScript(() => localStorage.setItem('intelligent-resume.locale', 'en-US'))

  const thirdVersion = {
    id: 12, resumeId: 1, versionNo: 3, sourceType: 'MANUAL', resumeJson: null, optimizationSummary: null,
    createdAt: '2026-08-12T09:00:00Z', archivedAt: null, restoredFromVersionId: null, generationContext: null,
  }
  const staleJson = { basics: { name: 'POISON-STALE-V12', email: 'stale@example.com' } }
  const versionRequests: Record<string, number> = {}
  let releaseStale: () => void = () => {}
  const staleServed = new Promise<void>((resolve) => { releaseStale = resolve })

  await page.route('**/api/auth/refresh', route => route.fulfill({ json: response({ accessToken: 'compare-token' }) }))
  await page.route('**/api/auth/me', route => route.fulfill({ json: response({ id: 1, username: 'compare-user', email: 'compare@example.com' }) }))
  await page.route('**/api/resumes/1', route => route.fulfill({ json: response(resume) }))
  await page.route('**/api/resumes/1/versions**', route => route.fulfill({ json: response([...versions, thirdVersion]) }))
  await page.route('**/api/resume-versions/*', async (route) => {
    const versionId = route.request().url().split('/').pop() ?? ''
    versionRequests[versionId] = (versionRequests[versionId] ?? 0) + 1
    if (versionId === '12') {
      // v12 的响应被延迟，模拟「旧请求晚于新请求返回」
      await new Promise(resolve => setTimeout(resolve, 400))
      await route.fulfill({ json: response({ ...thirdVersion, resumeJson: staleJson }) })
      releaseStale()
      return
    }
    const fixture = versionId === '10' ? versions[0] : versions[1]
    const json = versionId === '10' ? baseJson : compareJson
    await route.fulfill({ json: response({ ...fixture, resumeJson: json }) })
  })

  await page.goto('/resumes/1/compare')
  await expect(page.locator('.diff-summary')).toBeVisible()
  // 初始加载每个版本只请求一次（此前 onMounted 与 watch 各触发一次，共 2 次）
  expect(versionRequests['10']).toBe(1)
  expect(versionRequests['11']).toBe(1)

  // 连续切换比较版本：先选 12（慢），再切回 11（快）
  const compareSelect = page.locator('.compare-selectors select').nth(1)
  await compareSelect.selectOption('12')
  await compareSelect.selectOption('11')
  const nameCompareSide = page.locator('.field-diff-row.field-modified').filter({ hasText: 'Name' }).locator('.field-side.compare')
  await expect(nameCompareSide).toContainText('Compare Candidate')

  // 等延迟的 v12 响应真正返回后再断言：旧 diff 不得覆盖最新选择
  await staleServed
  await page.waitForTimeout(150)
  await expect(page.getByText('POISON-STALE-V12')).toHaveCount(0)
  await expect(nameCompareSide).toContainText('Compare Candidate')
  expect(versionRequests['12']).toBe(1)
  expect(versionRequests['11']).toBe(2)
})
