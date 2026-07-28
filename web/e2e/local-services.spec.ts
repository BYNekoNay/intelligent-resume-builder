import { expect, test, type Page } from '@playwright/test'
import { execFileSync } from 'node:child_process'

const apiBaseUrl = process.env.LOCAL_E2E_API_URL ?? 'http://localhost:8080'
if (!new Set([
  'http://localhost:8080',
  'http://127.0.0.1:8080',
  'http://127.0.0.1:8081',
]).has(apiBaseUrl)) {
  throw new Error('LOCAL_E2E_API_URL must be an approved loopback API origin.')
}

async function registerSyntheticAccount(page: Page, prefix: string, suffix: string) {
  await page.goto('/register')
  const inputs = page.locator('input')
  await inputs.nth(0).fill(`${prefix}${suffix}`)
  await inputs.nth(1).fill(`${prefix}-${suffix}@example.invalid`)
  await inputs.nth(2).fill(`LocalRun-${suffix}!`)

  const [response] = await Promise.all([
    page.waitForResponse(candidate => candidate.url().endsWith('/api/auth/register')),
    page.locator('form button[type="submit"]').click(),
  ])
  const responseBody = await response.text()
  expect(response.status(), `Registration failed: ${responseBody}`).toBe(201)
  const payload = JSON.parse(responseBody) as { data: { accessToken: string } }
  await expect(page).toHaveURL(/\/career-materials$/)
  return payload.data.accessToken
}

async function switchToEnglish(page: Page) {
  const englishButton = page.getByRole('button', { name: 'EN', exact: true })
  await englishButton.click()
  await expect(englishButton).toHaveAttribute('aria-pressed', 'true')
}

function invokePdfFault(action: 'StopPdf' | 'StartPdf') {
  execFileSync('powershell.exe', [
    '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', '..\\scripts\\Invoke-LocalFault.ps1', '-Action', action,
  ], { cwd: process.cwd(), stdio: 'pipe', timeout: 60_000 })
}

test.describe('@local-services local application smoke', () => {
  test.skip(process.env.LOCAL_E2E !== 'true', 'Runs only against explicitly started local services')

  test('loads the local login route without recording browser diagnostics', async ({ page }) => {
    await page.goto('/login')
    await expect(page).toHaveURL(/\/login$/)
    await expect(page.getByRole('heading')).toBeVisible()
  })

  test('creates a synthetic account through the visible registration flow and cleans it up', async ({ page }) => {
    const suffix = `${Date.now()}${Math.floor(Math.random() * 10000)}`
    const accessToken = await registerSyntheticAccount(page, 'local', suffix)

    const cleanup = await page.request.delete(`${apiBaseUrl}/api/auth/me`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    })
    expect(cleanup.status()).toBe(200)
  })

  test('imports a text resume, lets the user correct it, and creates an editable draft', async ({ page }) => {
    test.setTimeout(90_000)
    const suffix = `${Date.now()}${Math.floor(Math.random() * 10000)}`
    const title = `Imported UI resume ${suffix}`
    let accessToken: string | undefined

    try {
      accessToken = await registerSyntheticAccount(page, 'import', suffix)
      await switchToEnglish(page)

      await page.goto('/ai-consent')
      await page.locator('.consent-card button').click()
      await expect(page.locator('.consent-card')).toContainText('GRANTED')

      await page.goto('/resume-import')
      await page.locator('input[type="file"]').setInputFiles({
        name: 'existing-resume.txt',
        mimeType: 'text/plain',
        buffer: Buffer.from('Java engineer. Built a Spring Boot service and validated local MySQL workflows.'),
      })
      const [importResponse] = await Promise.all([
        page.waitForResponse(response => response.url().endsWith('/api/resume-imports/parse')),
        page.locator('form button').click(),
      ])
      expect(importResponse.status(), `Resume import failed: ${await importResponse.text()}`).toBe(200)
      const extracted = page.getByLabel('Extracted text')
      await expect(extracted).toBeVisible()
      await extracted.fill('Java engineer. Built a Spring Boot service and verified local MySQL workflows.')
      await page.getByRole('button', { name: 'Use corrected text' }).click()
      await expect(page).toHaveURL(/\/material-generation$/)
      const materialText = page.locator('textarea').first()
      await expect(materialText).toHaveValue(/verified local MySQL workflows/)
      await page.getByRole('button', { name: 'Generate structured draft' }).click()
      const titleInput = page.locator('article.workspace-card input')
      await expect(titleInput).toBeVisible()
      await titleInput.fill(title)
      await page.locator('article.workspace-card button.btn-secondary').click()
      await expect(page).toHaveURL(/\/resumes\/\d+$/)
      await expect(page.locator('.workspace-page')).toContainText(title)
    } finally {
      if (accessToken) {
        const cleanup = await page.request.delete(`${apiBaseUrl}/api/auth/me`, {
          headers: { Authorization: `Bearer ${accessToken}` },
          timeout: 5_000,
        })
        expect(cleanup.status()).toBe(200)
      }
    }
  })

  test('shows PDF failure in the UI and retries successfully after the local renderer recovers', async ({ page }) => {
    test.skip(process.env.LOCAL_E2E_PDF_RECOVERY !== 'true', 'Runs only when the caller explicitly allows controlled PDF service restart')
    test.setTimeout(90_000)
    const suffix = `${Date.now()}${Math.floor(Math.random() * 10000)}`
    const resumeTitle = `PDF recovery resume ${suffix}`
    let accessToken: string | undefined
    let rendererStopped = false

    try {
      accessToken = await registerSyntheticAccount(page, 'pdf', suffix)
      await switchToEnglish(page)

      await page.goto('/resumes')
      const resumeForm = page.locator('form').first()
      await resumeForm.locator('input').nth(0).fill(resumeTitle)
      await resumeForm.locator('input').nth(1).fill('PDF Recovery Candidate')
      await resumeForm.locator('button').click()
      await page.locator('.job-card a').filter({ hasText: resumeTitle }).click()

      invokePdfFault('StopPdf')
      rendererStopped = true
      await page.locator('.version-card').first().locator('button').nth(1).click()
      await expect(page).toHaveURL(/\/exports\/\d+$/)
      await expect.poll(async () => page.locator('.workspace-card').textContent()).toContain('FAILED')
      await expect(page.locator('.workspace-card .form-error')).toBeVisible()

      invokePdfFault('StartPdf')
      rendererStopped = false
      await page.locator('.workspace-card button.btn-secondary').click()
      await expect.poll(async () => page.locator('.workspace-card').textContent(), { timeout: 30_000 }).toContain('SUCCESS')
    } finally {
      if (rendererStopped) invokePdfFault('StartPdf')
      if (accessToken) {
        const cleanup = await page.request.delete(`${apiBaseUrl}/api/auth/me`, {
          headers: { Authorization: `Bearer ${accessToken}` },
          timeout: 5_000,
        })
        expect(cleanup.status()).toBe(200)
      }
    }
  })

  test('runs the core resume journey through the visible local UI', async ({ page }) => {
    test.setTimeout(150_000)
    const suffix = `${Date.now()}${Math.floor(Math.random() * 10000)}`
    const resumeTitle = `Local UI resume ${suffix}`
    const materialTitle = `Local UI project ${suffix}`
    const jobTitle = `Local UI engineer ${suffix}`
    let accessToken: string | undefined

    try {
      accessToken = await registerSyntheticAccount(page, 'journey', suffix)
      await switchToEnglish(page)

      const materialForm = page.locator('form').first()
      await materialForm.locator('input').fill(materialTitle)
      await materialForm.locator('textarea').first().fill('Built a local validation project with Java and Spring Boot.')
      await materialForm.locator('button').click()
      await expect(page.getByText(materialTitle)).toBeVisible()

      await page.goto('/resumes')
      const resumeForm = page.locator('form').first()
      await resumeForm.locator('input').nth(0).fill(resumeTitle)
      await resumeForm.locator('input').nth(1).fill('Local Journey Candidate')
      await resumeForm.locator('button').click()
      const resumeLink = page.locator('.job-card a').filter({ hasText: resumeTitle })
      await expect(resumeLink).toBeVisible()

      await page.goto('/jobs')
      const jobForm = page.locator('form').first()
      await jobForm.locator('input').nth(0).fill(jobTitle)
      await jobForm.locator('input').nth(1).fill('Local Validation Co')
      await jobForm.locator('textarea').fill('Java, Spring Boot, and MySQL experience required.')
      await jobForm.locator('button').click()
      const jobCard = page.locator('.job-card').filter({ hasText: jobTitle })
      await expect(jobCard).toBeVisible()

      await page.goto('/ai-consent')
      await page.locator('.consent-card button').click()
      await expect(page.locator('.consent-card')).toContainText('GRANTED')

      await page.goto('/jobs')
      const generatedJob = page.locator('.job-card').filter({ hasText: jobTitle })
      await generatedJob.locator('button.btn-primary').click()
      await expect(page).toHaveURL(/\/generate\?jdId=\d+$/)
      await page.getByRole('button', { name: 'Next: select materials' }).click()
      await page.getByRole('button', { name: 'Must use' }).click()
      await page.getByRole('button', { name: 'Next: start generation' }).click()
      await page.getByRole('button', { name: 'Start AI selection' }).click()
      await expect(page).toHaveURL(/\/generate\/materials\?taskId=\d+$/)
      await expect.poll(async () => page.locator('.task-status').textContent()).toContain('SUCCESS')

      const confirmationItems = page.locator('.confirmation-item')
      for (let index = 0; index < await confirmationItems.count(); index += 1) {
        await confirmationItems.nth(index).locator('button').first().click()
      }
      const confirmationSubmit = page.locator('.dialog-actions button.btn-primary').first()
      await expect(confirmationSubmit).toBeEnabled()
      await confirmationSubmit.click()
      await expect(page).toHaveURL(/\/resumes$/)

      await page.locator('.job-card a').filter({ hasText: resumeTitle }).click()
      const versionCard = page.locator('.version-card').first()
      await expect(versionCard).toBeVisible()
      await versionCard.locator('button').nth(0).click()
      await expect(page).toHaveURL(/\/match\/\d+$/)
      await expect(page.locator('.workspace-page')).not.toContainText('无法加载')

      await page.goBack()
      await expect(page).toHaveURL(/\/resumes\/\d+$/)
      await page.locator('.version-card').first().locator('button').nth(1).click()
      await expect(page).toHaveURL(/\/exports\/\d+$/)
      await expect.poll(async () => page.locator('.workspace-card').textContent(), { timeout: 30_000 }).toContain('SUCCESS')

      await page.goto('/communications')
      const communicationSelects = page.locator('select')
      await communicationSelects.nth(0).selectOption({ label: resumeTitle })
      await communicationSelects.nth(1).selectOption({ index: 1 })
      await communicationSelects.nth(2).selectOption({ index: 1 })
      await communicationSelects.nth(3).selectOption('EMAIL')
      await page.locator('form button').click()
      const draftEditor = page.getByLabel('Editable draft')
      await expect(draftEditor).toBeVisible()
      await draftEditor.fill('Edited local UI application email.')
      await page.getByRole('button', { name: 'Use in application' }).click()
      await expect(page).toHaveURL(/\/applications$/)
      await expect(page.locator('textarea').nth(1)).toHaveValue('Edited local UI application email.')
      await page.getByRole('button', { name: 'Create draft' }).click()
      await expect(page.locator('.application-card')).toHaveCount(1)

      await page.reload()
      const applicationCard = page.locator('.application-card').first()
      await expect(applicationCard).toBeVisible()
      const applicationStatus = applicationCard.locator('select')
      await expect(applicationStatus).toHaveValue('DRAFT')
      await Promise.all([
        page.waitForResponse(response => response.url().includes('/api/applications/') && response.url().endsWith('/status') && response.status() === 200),
        applicationStatus.selectOption('APPLIED'),
      ])
      await expect(applicationStatus).toHaveValue('APPLIED')
      await page.reload()
      await expect(page.locator('.application-card').first().locator('select')).toHaveValue('APPLIED')

      await page.goto('/ats')
      const atsSelects = page.locator('form select')
      await atsSelects.nth(0).selectOption({ label: resumeTitle })
      await atsSelects.nth(1).selectOption({ index: 1 })
      await atsSelects.nth(2).selectOption({ index: 1 })
      await page.locator('form button').click()
      const atsResult = page.locator('article.workspace-card').last()
      await expect(atsResult.locator('.score-grid p')).toHaveCount(3)
      await expect(atsResult.getByRole('heading', { name: 'Priority changes' })).toBeVisible()
      await expect(atsResult.getByRole('heading', { name: 'Passed checks' })).toBeVisible()
      await expect(atsResult.getByRole('heading', { name: 'Risks and evidence' })).toBeVisible()

      await page.goto('/interviews')
      const interviewSelects = page.locator('select')
      await interviewSelects.nth(0).selectOption('EXTERNAL_RESUME')
      await page.locator('textarea').first().fill('Java engineer with local validation project experience.')
      await interviewSelects.nth(2).selectOption({ index: 1 })
      await page.locator('form button').click()
      await expect(page.locator('textarea').first()).toBeVisible()
      const answerInput = page.getByLabel('Your answer')
      const finalAnswer = `Final local interview answer ${suffix} with a measured 30 percent result.`
      const answers = [
        'I designed the local project, verified its Java and Spring flows, and documented the outcome.',
        'I handled a MySQL reliability issue, took action with validation, and reduced failures by 20 percent.',
        finalAnswer,
      ]
      for (const interviewAnswer of answers) {
        await answerInput.fill(interviewAnswer)
        await Promise.all([
          page.waitForResponse(response => response.url().includes('/api/interviews/') && response.url().endsWith('/answer') && response.status() === 200),
          page.locator('form button').click(),
        ])
        await expect(page.getByText(/Round score:/)).toBeVisible()
      }
      await expect(page.getByRole('heading', { name: 'Interview complete' })).toBeVisible()
      await page.getByRole('button', { name: 'Interview report' }).click()
      await expect(page.getByText(/Total score:/)).toBeVisible()
      await expect(page.locator('article').filter({ hasText: 'Resume suggestions' })).toBeVisible()
      await page.getByRole('button', { name: 'Save to answer assets' }).click()
      await expect(page.getByText('Saved to answer assets.')).toBeVisible()

      await page.goto('/interview-assets')
      const assetFilter = page.locator('form.compact-form')
      const unrelatedAnswer = `Unscoped answer asset ${suffix}`
      const assetEditor = page.locator('form.workspace-card').nth(1)
      await assetEditor.getByLabel('Question').fill(`Unscoped question ${suffix}`)
      await assetEditor.getByLabel('Original answer').fill(unrelatedAnswer)
      await assetEditor.getByRole('button', { name: 'Save asset' }).click()
      await expect(page.getByText(unrelatedAnswer, { exact: true })).toBeVisible()
      await assetFilter.locator('select').selectOption({ index: 1 })
      await assetFilter.getByRole('button', { name: 'Search' }).click()
      await expect(page.getByText(finalAnswer, { exact: true })).toBeVisible()
      await expect(page.getByText(unrelatedAnswer, { exact: true })).toHaveCount(0)

      await page.goto('/ai-consent')
      await page.locator('.consent-card button').click()
      await expect(page.locator('.consent-card')).toContainText('WITHDRAWN')
      await page.goto('/communications')
      const withdrawnSelects = page.locator('select')
      await withdrawnSelects.nth(0).selectOption({ label: resumeTitle })
      await withdrawnSelects.nth(1).selectOption({ index: 1 })
      await withdrawnSelects.nth(2).selectOption({ index: 1 })
      await page.locator('form button').click()
      await expect(page.getByRole('alert')).toBeVisible()
      await page.goto('/resumes')
      await expect(page.getByText(resumeTitle)).toBeVisible()
    } finally {
      if (accessToken) {
        const cleanup = await page.request.delete(`${apiBaseUrl}/api/auth/me`, {
          headers: { Authorization: `Bearer ${accessToken}` },
          timeout: 5_000,
        })
        expect(cleanup.status()).toBe(200)
      }
    }
  })
})
