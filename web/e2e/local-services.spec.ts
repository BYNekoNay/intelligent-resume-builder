import { expect, test } from '@playwright/test'
import { execFileSync } from 'node:child_process'

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
    let accessToken: string | undefined
    let registrationUrl: string | undefined
    page.on('request', request => {
      if (request.url().includes('/api/auth/register')) registrationUrl = request.url()
    })
    page.on('response', async response => {
      if (response.url().endsWith('/api/auth/register') && response.status() === 201) {
        accessToken = ((await response.json()) as { data: { accessToken: string } }).data.accessToken
      }
    })

    await page.goto('/register')
    const inputs = page.locator('input')
    await inputs.nth(0).fill(`local${suffix}`)
    await inputs.nth(1).fill(`local-${suffix}@example.invalid`)
    await inputs.nth(2).fill(`LocalRun-${suffix}!`)
    await page.locator('form button[type="submit"]').click()
    await expect.poll(() => registrationUrl).toBe('http://127.0.0.1:8080/api/auth/register')
    await expect(page).toHaveURL(/\/career-materials$/)
    await expect.poll(() => accessToken).toBeTruthy()

    const cleanup = await page.request.delete('http://127.0.0.1:8080/api/auth/me', {
      headers: { Authorization: `Bearer ${accessToken}` },
    })
    expect(cleanup.status()).toBe(200)
  })

  test('imports a text resume, lets the user correct it, and creates an editable draft', async ({ page }) => {
    const suffix = `${Date.now()}${Math.floor(Math.random() * 10000)}`
    const title = `Imported UI resume ${suffix}`
    let accessToken: string | undefined
    page.on('response', async response => {
      if (response.url().endsWith('/api/auth/register') && response.status() === 201) {
        accessToken = ((await response.json()) as { data: { accessToken: string } }).data.accessToken
      }
    })

    try {
      await page.goto('/register')
      const inputs = page.locator('input')
      await inputs.nth(0).fill(`import${suffix}`)
      await inputs.nth(1).fill(`import-${suffix}@example.invalid`)
      await inputs.nth(2).fill(`LocalRun-${suffix}!`)
      await page.locator('form button[type="submit"]').click()
      await expect.poll(() => accessToken).toBeTruthy()

      await page.goto('/ai-consent')
      await page.locator('.consent-card button').click()
      await expect(page.locator('.consent-card')).toContainText('GRANTED')

      await page.goto('/resume-import')
      await page.locator('input[type="file"]').setInputFiles({
        name: 'existing-resume.txt',
        mimeType: 'text/plain',
        buffer: Buffer.from('Java engineer. Built a Spring Boot service and validated local MySQL workflows.'),
      })
      await page.locator('form button').click()
      const extracted = page.getByLabel('Extracted text')
      await expect(extracted).toBeVisible()
      await extracted.fill('Java engineer. Built a Spring Boot service and verified local MySQL workflows.')
      await page.getByRole('button', { name: 'Use corrected text' }).click()
      await expect(page).toHaveURL(/\/material-generation$/)
      const materialText = page.locator('textarea').first()
      await expect(materialText).toHaveValue(/verified local MySQL workflows/)
      await page.locator('form button').click()
      const titleInput = page.locator('article.workspace-card input')
      await expect(titleInput).toBeVisible()
      await titleInput.fill(title)
      await page.locator('article.workspace-card button.btn-secondary').click()
      await expect(page).toHaveURL(/\/resumes\/\d+$/)
      await expect(page.locator('.workspace-page')).toContainText(title)
    } finally {
      if (accessToken) {
        const cleanup = await page.request.delete('http://127.0.0.1:8080/api/auth/me', {
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
    page.on('response', async response => {
      if (response.url().endsWith('/api/auth/register') && response.status() === 201) {
        accessToken = ((await response.json()) as { data: { accessToken: string } }).data.accessToken
      }
    })

    try {
      await page.goto('/register')
      const inputs = page.locator('input')
      await inputs.nth(0).fill(`pdf${suffix}`)
      await inputs.nth(1).fill(`pdf-${suffix}@example.invalid`)
      await inputs.nth(2).fill(`LocalRun-${suffix}!`)
      await page.locator('form button[type="submit"]').click()
      await expect.poll(() => accessToken).toBeTruthy()

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
        const cleanup = await page.request.delete('http://127.0.0.1:8080/api/auth/me', {
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

    page.on('response', async response => {
      if (response.url().endsWith('/api/auth/register') && response.status() === 201) {
        accessToken = ((await response.json()) as { data: { accessToken: string } }).data.accessToken
      }
    })

    try {
      await page.goto('/register')
      const registrationInputs = page.locator('input')
      await registrationInputs.nth(0).fill(`journey${suffix}`)
      await registrationInputs.nth(1).fill(`journey-${suffix}@example.invalid`)
      await registrationInputs.nth(2).fill(`LocalRun-${suffix}!`)
      await page.locator('form button[type="submit"]').click()
      await expect(page).toHaveURL(/\/career-materials$/)
      await expect.poll(() => accessToken).toBeTruthy()

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
      await expect(page).toHaveURL(/\/jobs\/\d+\/generate\?taskId=\d+/)
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
      await page.locator('form button').click()
      await expect(page.locator('.application-card')).toHaveCount(1)

      await page.goto('/interviews')
      const interviewSelects = page.locator('select')
      await interviewSelects.nth(0).selectOption('EXTERNAL_RESUME')
      await page.locator('textarea').first().fill('Java engineer with local validation project experience.')
      await interviewSelects.nth(2).selectOption({ index: 1 })
      await page.locator('form button').click()
      await expect(page.locator('textarea').first()).toBeVisible()
      const answerInput = page.getByLabel('Your answer')
      await answerInput.fill('I designed the project, verified its flows, and documented the outcome.')
      await expect(answerInput).toHaveValue('I designed the project, verified its flows, and documented the outcome.')
      await page.locator('form button').click()
      await expect(page.getByText(/Round score:/)).toBeVisible()
      await page.getByRole('button', { name: 'Save to answer assets' }).click()
      await expect(page.getByText('Saved to answer assets.')).toBeVisible()

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
        const cleanup = await page.request.delete('http://127.0.0.1:8080/api/auth/me', {
          headers: { Authorization: `Bearer ${accessToken}` },
          timeout: 5_000,
        })
        expect(cleanup.status()).toBe(200)
      }
    }
  })
})
