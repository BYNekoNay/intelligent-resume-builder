import { expect, test, type Page } from '@playwright/test'

const now = '2026-07-22T10:00:00Z'
const response = (data: unknown) => ({ code: 0, message: 'ok', data, traceId: 'e2e' })
const resume = { id: 1, title: 'Backend resume', currentVersionId: 11, createdAt: now, updatedAt: now }
const version = { id: 11, resumeId: 1, versionNo: 1, sourceType: 'MANUAL', resumeJson: {}, optimizationSummary: null, createdAt: now }
const job = { id: 20, title: 'Backend Engineer', companyName: 'Example Systems', jdText: 'Java and Spring Boot', parsedKeywordsJson: null, parsedAt: null, parsedVersion: null, createdAt: now, updatedAt: now }

async function mockAuthenticatedApi(page: Page) {
  await page.route('**/api/auth/refresh', route => route.fulfill({ json: response({ accessToken: 'e2e-token' }) }))
  await page.route('**/api/auth/me', route => route.fulfill({ json: response({ id: 99, username: 'e2e-user', email: 'e2e@example.com' }) }))
  await page.route('**/api/resumes', route => route.fulfill({ json: response([resume]) }))
  await page.route('**/api/resumes/1', route => route.fulfill({ json: response(resume) }))
  await page.route('**/api/resumes/1/versions', route => route.fulfill({ json: response([version]) }))
  await page.route('**/api/jobs', route => route.fulfill({ json: response([job]) }))
  await page.route('**/api/applications', route => route.fulfill({ json: response([]) }))
}

test('takes an authenticated user from the home start action to the career materials workspace', async ({ page }) => {
  await mockAuthenticatedApi(page)

  await page.goto('/')
  await page.getByRole('link', { name: '开始使用' }).click()

  await expect(page).toHaveURL(/\/career-materials$/)
})

test('switches the application chrome and answer library between Chinese and English', async ({ page }) => {
  await mockAuthenticatedApi(page)

  await page.goto('/interview-assets')
  await expect(page.getByRole('heading', { name: '面试答案资产' })).toBeVisible()

  await page.getByRole('button', { name: 'EN' }).click()

  await expect(page.getByRole('link', { name: 'Workspace' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Interview Answer Assets' })).toBeVisible()
  await expect(page.getByRole('button', { name: '中文' })).toHaveAttribute('aria-pressed', 'false')

  await page.reload()

  await expect(page.locator('html')).toHaveAttribute('lang', 'en')
  await expect(page).toHaveTitle('ZhiLi · Intelligent Resume Builder')
  await expect(page.getByRole('heading', { name: 'Interview Answer Assets' })).toBeVisible()
})

test('switches the complete job description workspace content with the selected language', async ({ page }) => {
  await mockAuthenticatedApi(page)

  await page.goto('/jobs')
  await expect(page.getByRole('heading', { name: '目标岗位' })).toBeVisible()

  await page.getByRole('button', { name: 'EN' }).click()
  await expect(page.getByRole('heading', { name: 'Target Jobs' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'New job description' })).toBeVisible()

  await page.getByRole('button', { name: '中文' }).click()
  await expect(page.getByRole('heading', { name: '目标岗位' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '新建岗位描述' })).toBeVisible()
})

test('generates an editable communication draft and carries it into an application', async ({ page }) => {
  await mockAuthenticatedApi(page)
  let generationPayload: unknown
  await page.route('**/api/communications/generate', async route => {
    generationPayload = route.request().postDataJSON()
    await route.fulfill({ json: response({ type: 'EMAIL', draft: 'Hello Example Systems, I would like to apply.', sentAutomatically: false, requiresManualConfirmation: true }) })
  })
  await page.goto('/communications')
  await page.locator('select').nth(1).selectOption('11')
  await page.locator('select').nth(2).selectOption('20')
  await page.locator('select').nth(3).selectOption('EMAIL')
  await page.getByRole('button', { name: 'Generate draft' }).click()
  await expect.poll(() => generationPayload).toEqual({ resumeVersionId: 11, jobDescriptionId: 20, type: 'EMAIL' })
  const editor = page.getByLabel('Editable draft')
  await editor.fill('Edited email body')
  await page.getByRole('button', { name: 'Use in application' }).click()
  await expect(page).toHaveURL(/\/applications$/)
  await expect(page.locator('textarea').nth(1)).toHaveValue('Edited email body')
})

test('requires decisions for every pending job-generation item before confirmation', async ({ page }) => {
  await mockAuthenticatedApi(page)
  const task = { id: 42, taskType: 'JOB_GENERATION', status: 'SUCCESS', confirmationStatus: 'PENDING', retryCount: 0, resultResumeVersionId: null, errorMessage: null, createdAt: now, updatedAt: now, resultJson: { draftResumeJson: { basics: {}, work: [{ company: 'Example Systems', _source: 'material:101', _pending: true }], education: [], skills: [{ name: 'Java', _source: 'material:102', _pending: true }], projects: [] } } }
  await page.route('**/api/ai/tasks/42', route => route.fulfill({ json: response(task) }))
  let confirmation: unknown
  await page.route('**/api/ai/tasks/42/confirm', async route => { confirmation = route.request().postDataJSON(); await route.fulfill({ json: response({ resumeVersionId: 2, versionNo: 2, resultResumeVersionId: 2, rejectedPaths: [], newMaterialIds: [] }) }) })
  await page.goto('/jobs/20/generate?taskId=42')
  const cards = page.locator('.confirmation-item')
  await expect(cards).toHaveCount(2)
  const submit = page.locator('.dialog-actions button').first()
  await expect(submit).toBeDisabled()
  await cards.nth(0).locator('button').first().click()
  await expect(submit).toBeDisabled()
  await cards.nth(1).locator('button').first().click()
  await submit.click()
  await expect.poll(() => confirmation).toMatchObject({ items: [{ outputPath: '/work/0', decision: 'ACCEPT' }, { outputPath: '/skills/0', decision: 'ACCEPT' }] })
  await expect(page).toHaveURL(/\/resumes$/)
})

test('retries a failed PDF export through polling and downloads the completed document', async ({ page }) => {
  await mockAuthenticatedApi(page)
  const failed = { id: 7, resumeVersionId: 11, templateCode: 'classic', status: 'FAILED', fileSizeBytes: null, sha256: null, errorMessage: 'renderer unavailable', expiresAt: null, createdAt: now, updatedAt: now }
  const complete = { ...failed, status: 'SUCCESS', errorMessage: null, fileSizeBytes: 123, sha256: 'abc', expiresAt: '2026-07-23T10:00:00Z' }
  let retried = false; let reads = 0
  await page.route('**/api/exports/tasks/7', route => { reads += 1; return route.fulfill({ json: response(!retried ? failed : reads === 2 ? { ...failed, status: 'RUNNING', errorMessage: null } : complete) }) })
  await page.route('**/api/exports/tasks/7/retry', route => { expect(route.request().method()).toBe('POST'); retried = true; return route.fulfill({ json: response({ ...failed, status: 'PENDING', errorMessage: null }) }) })
  await page.route('**/api/exports/files/7', route => route.fulfill({ contentType: 'application/pdf', body: '%PDF-e2e' }))
  await page.goto('/exports/7')
  await expect(page.getByText('renderer unavailable')).toBeVisible()
  const actions = page.locator('.workspace-card button')
  await actions.first().click()
  await expect(page.getByText(/PENDING|RUNNING/)).toBeVisible()
  await expect(page.getByText(/SUCCESS/)).toBeVisible()
  const download = page.waitForEvent('download')
  await actions.first().click()
  expect((await download).suggestedFilename()).toBe('resume.pdf')
})

test('recovers a failed job-generation task through retry polling', async ({ page }) => {
  await mockAuthenticatedApi(page)
  const failed = { id: 43, taskType: 'JOB_GENERATION', status: 'FAILED', confirmationStatus: 'PENDING', retryCount: 0, resultJson: null, resultResumeVersionId: null, errorMessage: 'provider timeout', createdAt: now, updatedAt: now }
  const ready = { ...failed, status: 'SUCCESS', errorMessage: null, retryCount: 1, updatedAt: '2026-07-22T10:01:00Z', resultJson: { draftResumeJson: { basics: {}, work: [{ company: 'Example', _source: 'material:101', _pending: false }], education: [], skills: [], projects: [] } } }
  let retried = false; let reads = 0
  await page.route('**/api/ai/tasks/43', route => { reads += 1; return route.fulfill({ json: response(!retried ? failed : reads === 2 ? { ...failed, status: 'RUNNING', errorMessage: null } : ready) }) })
  await page.route('**/api/ai/tasks/43/retry', route => { expect(route.request().method()).toBe('POST'); retried = true; return route.fulfill({ json: response({ ...failed, status: 'PENDING' }) }) })
  await page.goto('/jobs/20/generate?taskId=43')
  await expect(page.getByText('provider timeout')).toBeVisible()
  await page.locator('.workspace-card button').click()
  await expect.poll(() => reads).toBeGreaterThanOrEqual(3)
  await expect(page.locator('code')).toContainText('/work/0')
})
