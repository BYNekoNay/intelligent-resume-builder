import { expect, test, type Page } from '@playwright/test'

const now = '2026-07-22T10:00:00Z'
const response = (data: unknown) => ({ code: 0, message: 'ok', data, traceId: 'e2e' })
const resume = { id: 1, title: 'Backend resume', currentVersionId: 11, jobDescriptionId: null, createdAt: now, updatedAt: now }
const version = { id: 11, resumeId: 1, versionNo: 1, sourceType: 'MANUAL', resumeJson: {}, optimizationSummary: null, createdAt: now }
const job = { id: 20, title: 'Backend Engineer', companyName: 'Example Systems', jdText: 'Java and Spring Boot', parsedKeywordsJson: null, parsedAt: null, parsedVersion: null, createdAt: now, updatedAt: now }

async function mockAuthenticatedApi(page: Page) {
  await page.route('**/api/auth/refresh', route => route.fulfill({ json: response({ accessToken: 'e2e-token' }) }))
  await page.route('**/api/auth/me', route => route.fulfill({ json: response({ id: 99, username: 'e2e-user', email: 'e2e@example.com' }) }))
  await page.route('**/api/resumes', route => route.fulfill({ json: response([resume]) }))
  await page.route('**/api/resumes/1', route => route.fulfill({ json: response(resume) }))
  await page.route('**/api/resumes/1/versions', route => route.fulfill({ json: response([version]) }))
  await page.route('**/api/jobs', route => route.fulfill({ json: response([job]) }))
  await page.route('**/api/career-materials*', route => route.fulfill({ json: response([]) }))
  await page.route('**/api/applications', route => route.fulfill({ json: response([]) }))
}

test('takes an authenticated user from the home start action to the career materials workspace', async ({ page }) => {
  await mockAuthenticatedApi(page)

  await page.goto('/')
  await page.getByRole('link', { name: '开始使用' }).click()

  await expect(page).toHaveURL(/\/career-materials$/)
})

test('persists resume typography and spacing controls with the edited version', async ({ page }) => {
  await mockAuthenticatedApi(page)
  const editorVersion = {
    ...version,
    resumeJson: {
      basics: { name: 'Alice', title: 'Engineer', summary: 'Builds reliable systems.' },
      work: [{ company: 'ACME', position: 'Engineer' }],
      education: [], skills: [], projects: [], certificates: [], languages: [],
      template: { code: 'classic' },
    },
  }
  let saved: any
  await page.route('**/api/resume-versions/11', route => route.fulfill({ json: response(editorVersion) }))
  await page.route('**/api/resumes/1/versions', async route => {
    if (route.request().method() === 'GET') return route.fulfill({ json: response([version]) })
    saved = route.request().postDataJSON()
    return route.fulfill({ json: response(editorVersion) })
  })

  await page.goto('/resumes/1/edit')
  const bodySize = page.getByLabel('正文字号滑杆')
  await expect(bodySize).toHaveValue('13')
  await bodySize.fill('16')
  await expect(page.locator('.resume-paper')).toHaveCSS('--resume-body-size', '16px')
  const headingSize = page.getByLabel('标题字号滑杆')
  await headingSize.fill('18')
  await expect(page.locator('.paper-header h2')).toHaveCSS('font-size', '41.5385px')
  await page.getByLabel('字体风格').selectOption('songti')
  await expect(page.locator('.resume-paper')).toHaveCSS('--resume-font-family', '"Songti SC", SimSun, serif')
  await page.getByRole('button', { name: '保存新版本' }).click()
  await expect.poll(() => saved).toMatchObject({ resumeJson: { layout: { bodyFontSize: 16, headingFontSize: 18, fontFamily: 'songti' } } })
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

test('opens the generation workbench with the selected job description', async ({ page }) => {
  await mockAuthenticatedApi(page)

  await page.goto('/jobs')
  await page.locator('.job-card .job-actions .btn-primary').click()

  await expect(page).toHaveURL(/\/generate\?jdId=20$/)
  await expect(page.locator('.jd-card.selected')).toContainText('Backend Engineer')
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
