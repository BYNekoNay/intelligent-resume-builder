import { expect, test, type Page, type TestInfo } from '@playwright/test'
import { execFileSync } from 'node:child_process'

const apiBaseUrl = process.env.LOCAL_E2E_API_URL ?? 'http://127.0.0.1:8080'
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

async function cleanupSyntheticAccount(page: Page, accessToken: string, testInfo: TestInfo) {
  if (testInfo.status === 'timedOut' || testInfo.status === 'interrupted') return
  testInfo.setTimeout(testInfo.timeout + 10_000)
  const cleanup = await page.request.delete(`${apiBaseUrl}/api/auth/me`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    timeout: 5_000,
  })
  expect(cleanup.status()).toBe(200)
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
    await expect(page.getByRole('heading', { name: '登录', exact: true })).toBeVisible()
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
      await expect(page.locator('.consent-card')).toContainText('Authorized')

      await page.goto('/resume-import')
      await page.locator('input[type="file"]').setInputFiles({
        name: 'existing-resume.txt',
        mimeType: 'text/plain',
        buffer: Buffer.from('Java engineer. Built a Spring Boot service and validated local MySQL workflows.'),
      })
      const [importResponse] = await Promise.all([
        page.waitForResponse(response => response.url().endsWith('/api/resume-imports/parse')),
        page.getByRole('button', { name: 'Parse file content' }).click(),
      ])
      expect(importResponse.status(), `Resume import failed: ${await importResponse.text()}`).toBe(200)
      const extracted = page.getByLabel('Editable text')
      await expect(extracted).toBeVisible()
      await extracted.fill('Java engineer. Built a Spring Boot service and verified local MySQL workflows.')
      await page.getByRole('button', { name: 'Confirm text and continue' }).click()
      await expect(page).toHaveURL(/\/material-generation$/)
      const materialText = page.locator('textarea').first()
      await expect(materialText).toHaveValue(/verified local MySQL workflows/)
      await page.getByRole('button', { name: 'Generate structured draft' }).click()
      const titleInput = page.getByLabel('Resume title')
      await expect(titleInput).toBeVisible({ timeout: 75_000 })
      await titleInput.fill(title)
      const [createResponse] = await Promise.all([
        page.waitForResponse(response => response.request().method() === 'POST' && response.url().endsWith('/api/resumes')),
        page.getByRole('button', { name: 'Confirm & create editable resume' }).click(),
      ])
      expect(createResponse.status(), `Resume creation failed: ${await createResponse.text()}`).toBe(201)
      await expect(page).toHaveURL(/\/resumes\/\d+$/, { timeout: 30_000 })
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
      await resumeForm.getByRole('button', { name: 'New resume' }).click()
      await page.locator('.resume-row-main').filter({ hasText: resumeTitle }).click()

      invokePdfFault('StopPdf')
      rendererStopped = true
      await page.locator('.version-card').first().getByRole('button', { name: 'Export PDF' }).click()
      await expect(page).toHaveURL(/\/exports\/\d+$/)
      await expect(page.getByText('PDF generation failed', { exact: true })).toBeVisible({ timeout: 30_000 })
      await expect(page.getByRole('button', { name: 'Retry export' })).toBeVisible()

      invokePdfFault('StartPdf')
      rendererStopped = false
      await page.getByRole('button', { name: 'Retry export' }).click()
      await expect(page.getByText('PDF is ready', { exact: true })).toBeVisible({ timeout: 30_000 })
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

  test('runs a real multi-round AI interview and saves the final answer asset', async ({ page }) => {
    test.setTimeout(600_000)
    const suffix = `${Date.now()}${Math.floor(Math.random() * 10000)}`
    let accessToken: string | undefined

    try {
      accessToken = await registerSyntheticAccount(page, 'interview', suffix)
      await switchToEnglish(page)

      await page.goto('/ai-consent')
      await page.locator('.consent-card button').click()
      await expect(page.locator('.consent-card')).toContainText('Authorized')

      await page.goto('/interviews')
      await page.locator('textarea').first().fill('Java backend engineer who built Spring Boot and MySQL services and improved P99 latency by 30 percent.')
      await page.locator('input[type="number"]').fill('4')
      page.once('dialog', dialog => dialog.accept())
      await page.getByRole('button', { name: 'Start interview' }).click()

      const answerInput = page.getByLabel('Your answer')
      await expect(answerInput).toBeVisible({ timeout: 75_000 })
      let finalAnswer = ''
      let completed = false
      for (let round = 1; round <= 6; round += 1) {
        finalAnswer = `Round ${round} ${suffix}: I diagnosed the bottleneck from metrics, changed the Java and MySQL implementation, validated it under production-like load, and measured a ${30 + round} percent P99 improvement.`
        await answerInput.fill(finalAnswer)
        const [answerResponse] = await Promise.all([
          page.waitForResponse(response => response.url().includes('/api/interviews/') && response.url().endsWith('/answer') && response.status() === 200, { timeout: 75_000 }),
          page.getByRole('button', { name: 'Submit answer' }).click(),
        ])
        const answerPayload = await answerResponse.json() as { data: { status: string } }
        await expect(page.getByRole('heading', { name: 'Answer review' })).toBeVisible()
        completed = answerPayload.data.status === 'COMPLETED'
        if (completed) break
        await expect(answerInput).toBeVisible()
      }

      expect(completed, 'Interview did not complete within the configured maximum range').toBe(true)
      await expect(page.getByRole('heading', { name: /Interview complete/ })).toBeVisible()
      await page.getByRole('button', { name: 'Interview report' }).click()
      await expect(page.getByText('Total score', { exact: true })).toBeVisible()
      await page.getByRole('button', { name: 'Save as answer asset' }).click()
      await expect(page.getByText('Saved', { exact: true })).toBeVisible()

      await page.goto('/interview-assets')
      await expect(page.getByText(finalAnswer, { exact: true })).toBeVisible()
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

  test('runs the core resume journey through the visible local UI', async ({ page }, testInfo) => {
    test.setTimeout(900_000)
    const suffix = `${Date.now()}${Math.floor(Math.random() * 10000)}`
    const resumeTitle = `Local UI resume ${suffix}`
    const materialTitle = `Local UI project ${suffix}`
    const jobTitle = `Local UI engineer ${suffix}`
    let accessToken: string | undefined

    try {
      accessToken = await registerSyntheticAccount(page, 'journey', suffix)
      await switchToEnglish(page)

      await page.locator('.new-icon-action').click()
      const materialForm = page.locator('.material-form')
      await materialForm.locator('input').fill(materialTitle)
      await materialForm.locator('textarea').first().fill('Built a local validation project with Java and Spring Boot.')
      await materialForm.getByRole('button', { name: 'Save material' }).click()
      await expect(page.getByRole('heading', { name: materialTitle })).toBeVisible()

      await page.goto('/resumes')
      const resumeForm = page.locator('form').first()
      await resumeForm.locator('input').nth(0).fill(resumeTitle)
      await resumeForm.locator('input').nth(1).fill('Local Journey Candidate')
      await resumeForm.getByRole('button', { name: 'New resume' }).click()
      const resumeLink = page.locator('.resume-row-main').filter({ hasText: resumeTitle })
      await expect(resumeLink).toBeVisible()

      await page.goto('/jobs')
      const jobForm = page.locator('form').first()
      await jobForm.locator('input').nth(0).fill(jobTitle)
      await jobForm.locator('input').nth(1).fill('Local Validation Co')
      await jobForm.locator('textarea').fill('Java, Spring Boot, and MySQL experience required.')
      await jobForm.getByRole('button', { name: 'Save job' }).click()
      const jobCard = page.locator('.job-card').filter({ hasText: jobTitle })
      await expect(jobCard).toBeVisible()

      await page.goto('/ai-consent')
      await page.locator('.consent-card button').click()
      await expect(page.locator('.consent-card')).toContainText('Authorized')

      await page.goto('/jobs')
      const generatedJob = page.locator('.job-card').filter({ hasText: jobTitle })
      await generatedJob.locator('button.btn-primary').click()
      await expect(page).toHaveURL(/\/generate\?jdId=\d+$/)
      await page.getByRole('button', { name: 'Next: select materials' }).click()
      await page.getByRole('button', { name: 'Must use' }).click()
      await page.getByRole('button', { name: 'Next: start generation' }).click()
      await page.getByRole('button', { name: 'Start AI selection' }).click()
      await expect(page).toHaveURL(/\/generate\/materials\?taskId=\d+$/)
      await expect(page.getByRole('heading', { name: 'Confirm materials for this resume' })).toBeVisible({ timeout: 180_000 })
      await page.getByRole('button', { name: 'Confirm selection & generate resume' }).click()
      await expect(page).toHaveURL(/\/generate\/confirm\?taskId=\d+$/)

      const acceptButtons = page.getByRole('button', { name: 'Accept', exact: true })
      await expect(acceptButtons.first()).toBeVisible({ timeout: 180_000 })
      const reviewSections = page.locator('.section-navigation__item')
      for (let sectionIndex = 0; sectionIndex < await reviewSections.count(); sectionIndex += 1) {
        await reviewSections.nth(sectionIndex).click()
        const pendingAcceptButtons = page.getByRole('button', { name: 'Accept', exact: true, pressed: false })
        while (await pendingAcceptButtons.count()) {
          await pendingAcceptButtons.first().click()
        }
      }
      const confirmationSubmit = page.getByRole('button', { name: 'Confirm & create resume' })
      await expect(confirmationSubmit).toBeEnabled()
      await confirmationSubmit.click()
      const existingResumeDialog = page.getByRole('heading', { name: 'Resume already exists' })
      await Promise.race([
        page.waitForURL(/\/resumes\/\d+$/),
        existingResumeDialog.waitFor({ state: 'visible' }),
      ])
      if (await existingResumeDialog.isVisible()) {
        await page.getByRole('button', { name: 'Update this resume' }).click()
      }
      await expect(page).toHaveURL(/\/resumes\/\d+$/)

      const versionCard = page.locator('.version-card').first()
      await expect(versionCard).toBeVisible()
      await versionCard.getByRole('button', { name: 'View JD coverage' }).click()
      await expect(page).toHaveURL(/\/match\/\d+$/)
      await expect(page.locator('.workspace-page')).not.toContainText('无法加载')

      await page.goBack()
      await expect(page).toHaveURL(/\/resumes\/\d+$/)
      await page.locator('.version-card').first().getByRole('button', { name: 'Export PDF' }).click()
      await expect(page).toHaveURL(/\/exports\/\d+$/)
      const pdfFailure = page.getByText('PDF generation failed', { exact: true })
      await Promise.race([
        page.getByText('PDF is ready', { exact: true }).waitFor({ state: 'visible', timeout: 30_000 }),
        pdfFailure.waitFor({ state: 'visible', timeout: 30_000 }),
      ])
      if (await pdfFailure.isVisible()) {
        await page.getByRole('button', { name: 'Retry export' }).click()
      }
      await expect(page.getByText('PDF is ready', { exact: true })).toBeVisible({ timeout: 30_000 })

      await page.goto('/communications')
      const communicationSelects = page.locator('select')
      await communicationSelects.nth(0).selectOption({ label: resumeTitle })
      await communicationSelects.nth(1).selectOption({ index: 1 })
      await communicationSelects.nth(2).selectOption({ index: 1 })
      await communicationSelects.nth(3).selectOption('EMAIL')
      await page.getByRole('button', { name: 'Use template' }).click()
      const draftEditor = page.getByLabel('Editable draft')
      await expect(draftEditor).toBeVisible({ timeout: 75_000 })
      await draftEditor.fill('Edited local UI application email.')
      await page.getByRole('button', { name: 'Use in application' }).click()
      await expect(page).toHaveURL(/\/applications$/)
      await expect(page.getByLabel('Email body')).toHaveValue('Edited local UI application email.')
      await page.getByRole('button', { name: 'Create draft' }).click()
      const applicationTicket = page.locator('.application-ticket').filter({ hasText: jobTitle })
      await expect(applicationTicket).toHaveCount(1)

      await page.reload()
      await expect(applicationTicket).toBeVisible()
      const applicationStatus = applicationTicket.getByRole('combobox', { name: 'Application stage' })
      await expect(applicationStatus).toHaveValue('DRAFT')
      await Promise.all([
        page.waitForResponse(response => response.url().includes('/api/applications/') && response.url().endsWith('/status') && response.status() === 200),
        applicationStatus.selectOption('APPLIED'),
      ])
      await expect(applicationStatus).toHaveValue('APPLIED')
      await page.reload()
      await expect(applicationTicket.getByRole('combobox', { name: 'Application stage' })).toHaveValue('APPLIED')

      await page.setViewportSize({ width: 1440, height: 900 })
      await page.goto('/')
      const nextAction = page.locator('.next-action-primary')
      await expect(nextAction).toContainText('Continue your recent resume')
      await expect(nextAction).toContainText(jobTitle)
      await expect(nextAction.getByRole('link', { name: 'Continue editing' })).toBeVisible()
      await page.screenshot({ path: testInfo.outputPath('local-dashboard-desktop-1440x900.png'), fullPage: true })
      await page.locator('.workflow-card').filter({ hasText: 'Find expression gaps' }).click()
      await expect(page).toHaveURL(/\/ats$/)

      const atsSelects = page.locator('form select')
      await atsSelects.nth(0).selectOption({ label: resumeTitle })
      await atsSelects.nth(1).selectOption({ index: 1 })
      await atsSelects.nth(2).selectOption({ index: 1 })
      await page.getByRole('button', { name: 'Run AI check' }).click()
      const atsResult = page.locator('article').filter({ has: page.getByRole('heading', { name: 'ATS Check Report' }) })
      await expect(atsResult.getByText('Rule score', { exact: true })).toBeVisible({ timeout: 90_000 })
      await expect(atsResult.getByText('Structure', { exact: true })).toBeVisible()
      await expect(atsResult.getByText('Keyword coverage', { exact: true })).toBeVisible()
      await expect(atsResult.getByRole('heading', { name: 'Rule priorities' })).toBeVisible()
      await expect(atsResult.getByRole('heading', { name: 'Passed checks' })).toBeVisible()
      await expect(atsResult.getByRole('heading', { name: 'Rule risks and evidence' })).toBeVisible()

      const mappedEditorLink = atsResult.locator('.insight-edit-link[href*="section=basics"], .insight-edit-link[href*="section=work"]').first()
      await expect(mappedEditorLink).toBeVisible()
      const mappedEditorHref = await mappedEditorLink.getAttribute('href')
      expect(mappedEditorHref).toMatch(/^\/resumes\/\d+\/edit\?section=(basics|work)/)
      await page.screenshot({ path: testInfo.outputPath('local-ats-desktop-1440x900.png'), fullPage: true })
      await mappedEditorLink.click()

      const returnToReport = page.getByRole('navigation', { name: 'ATS report navigation' })
        .getByRole('link', { name: 'Return to ATS report' })
      await expect(returnToReport).toBeVisible()
      const reportHref = await returnToReport.getAttribute('href')
      expect(reportHref).toMatch(/^\/ats\?result=\d+$/)
      await expect(page.getByRole('form', { name: 'Resume content editor' })).toHaveAttribute('aria-readonly', 'false')
      if (mappedEditorHref!.includes('section=work')) {
        const company = page.getByLabel('Company').first()
        if (await company.count() === 0) {
          await page.getByRole('button', { name: 'Add work experience' }).click()
        }
        await expect(company).toBeEditable()
        await company.fill(`Current Version Systems ${suffix}`)
      } else {
        const name = page.getByLabel('Name')
        await expect(name).toBeEditable()
        await name.fill(`Current Version Candidate ${suffix}`)
      }
      await page.locator('.editor-save-dock').getByRole('button', { name: 'Save new version' }).click()
      await expect(page).toHaveURL(/\/resumes\/\d+$/)
      const newestVersion = page.locator('.version-card').first()
      const setCurrent = newestVersion.getByRole('button', { name: 'Set as current' })
      await expect(newestVersion).toBeVisible({ timeout: 10_000 })
      await expect(setCurrent).toBeVisible()
      page.once('dialog', dialog => dialog.accept())
      await Promise.all([
        page.waitForResponse(response => response.url().endsWith('/current-version') && response.status() === 200),
        setCurrent.click(),
      ])
      await expect(newestVersion.getByText('Current', { exact: true })).toBeVisible()

      await page.goto(reportHref!)
      const historicalEditorLink = atsResult.locator(`.insight-edit-link[href="${mappedEditorHref}"]`).first()
      await expect(historicalEditorLink).toBeVisible()
      await historicalEditorLink.click()
      await page.setViewportSize({ width: 390, height: 844 })
      await expect(page.getByRole('form', { name: 'Read-only analyzed resume version' })).toHaveAttribute('aria-readonly', 'true')
      await page.screenshot({ path: testInfo.outputPath('local-ats-editor-mobile-390x844.png'), fullPage: true })
      await page.getByRole('button', { name: 'Create editable version' }).click()
      await expect(page).toHaveURL(/sourceVersionId=\d+.*editVersionId=\d+/)
      await expect(page.getByRole('form', { name: 'Resume content editor' })).toHaveAttribute('aria-readonly', 'false')
      await expect(page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).resolves.toBe(true)

      await page.goto('/interviews')
      const interviewSelects = page.locator('select')
      await interviewSelects.nth(0).selectOption('EXTERNAL_RESUME')
      await page.locator('textarea').first().fill('Java engineer with local validation project experience.')
      await page.locator('input[type="number"]').fill('4')
      await interviewSelects.nth(2).selectOption({ index: 1 })
      await page.getByRole('button', { name: 'Start interview' }).click()
      await expect(page.getByLabel('Your answer')).toBeVisible({ timeout: 75_000 })
      const answerInput = page.getByLabel('Your answer')
      let finalAnswer = ''
      let completed = false
      for (let round = 1; round <= 6; round += 1) {
        const interviewAnswer = `Round ${round} ${suffix}: I inspected the evidence, implemented the Java and MySQL change, verified it under production-like load, and measured a ${20 + round} percent improvement.`
        finalAnswer = interviewAnswer
        await answerInput.fill(interviewAnswer)
        const [answerResponse] = await Promise.all([
          page.waitForResponse(response => response.url().includes('/api/interviews/') && response.url().endsWith('/answer') && response.status() === 200, { timeout: 75_000 }),
          page.getByRole('button', { name: 'Submit answer' }).click(),
        ])
        const answerPayload = await answerResponse.json() as { data: { status: string } }
        await expect(page.getByRole('heading', { name: 'Answer review' })).toBeVisible()
        completed = answerPayload.data.status === 'COMPLETED'
        if (completed) break
        await expect(answerInput).toBeVisible()
      }
      expect(completed, 'Interview did not reach COMPLETED within its configured maximum range').toBe(true)
      await expect(page.getByRole('heading', { name: /Interview complete/ })).toBeVisible()
      await page.getByRole('button', { name: 'Interview report' }).click()
      await expect(page.getByText('Total score', { exact: true })).toBeVisible()
      await expect(page.locator('article').filter({ hasText: 'Resume suggestions' })).toBeVisible()
      const [assetResponse] = await Promise.all([
        page.waitForResponse(response => response.url().endsWith('/api/interview-answer-assets'), { timeout: 30_000 }),
        page.getByRole('button', { name: 'Save as answer asset' }).click(),
      ])
      expect(assetResponse.status(), `Answer asset save failed: ${await assetResponse.text()}`).toBe(201)
      await expect(page.getByText('Saved', { exact: true })).toBeVisible({ timeout: 10_000 })

      await page.goto('/interview-assets')
      const assetFilter = page.locator('form').filter({ has: page.getByLabel('Job') })
      const unrelatedAnswer = `Unscoped answer asset ${suffix}`
      const assetEditor = page.locator('form').filter({ has: page.getByLabel('Question') })
      await expect(assetFilter).toBeVisible({ timeout: 10_000 })
      await expect(assetEditor).toBeVisible({ timeout: 10_000 })
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
      await expect(page.locator('.consent-card')).toContainText('Withdrawn')
      await page.goto('/communications')
      const withdrawnSelects = page.locator('select')
      await withdrawnSelects.nth(0).selectOption({ label: resumeTitle })
      await withdrawnSelects.nth(1).selectOption({ index: 1 })
      await withdrawnSelects.nth(2).selectOption({ index: 1 })
      await page.getByRole('button', { name: 'Generate with AI' }).click()
      await expect(page.getByRole('alert')).toBeVisible()
      await page.goto('/resumes')
      await expect(page.getByText(resumeTitle)).toBeVisible()
    } finally {
      if (accessToken) {
        await cleanupSyntheticAccount(page, accessToken, testInfo)
      }
    }
  })
})
