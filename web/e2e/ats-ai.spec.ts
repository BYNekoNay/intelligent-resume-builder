import { expect, test, type Page } from '@playwright/test'

const response = (data: unknown) => ({ code: 0, message: 'ok', data, traceId: 'ats-e2e' })
const ruleResult = {
  id: 77,
  totalScore: 72,
  checks: { structure: 100, keywordCoverage: 65, skillCoverage: 70, experienceCoverage: 60 },
  passedChecks: ['Core structure is complete'],
  risks: ['Missing one job keyword'],
  priorities: ['Add verified Redis evidence'],
  disclaimer: 'Rule scores and AI suggestions are guidance only.',
  analysisStatus: 'RULES_FALLBACK',
  analysisSource: 'RULES',
  aiTaskId: null,
  aiInsights: null,
  fallback: null,
}
const hybridResult = {
  ...ruleResult,
  analysisStatus: 'COMPLETED',
  analysisSource: 'HYBRID',
  aiTaskId: 88,
  aiInsights: {
    summary: 'The resume has direct Java evidence but only partial Redis evidence.',
    semanticCoverage: [{ requirement: 'Java', status: 'MATCHED', evidence: 'Built Java services', reason: 'Direct work evidence' }],
    evidenceFindings: [{ section: 'work', quote: 'Built Java services', assessment: 'Specific technology evidence', suggestion: 'Add verified scale' }],
    readabilityRisks: ['Keep conventional section headings'],
    prioritizedActions: [{ priority: 'P1', section: 'work', action: 'Add verified scale', basis: 'Evidence quality' }],
    confidence: 'MEDIUM',
  },
}

async function mockAtsPage(page: Page) {
  await page.addInitScript(() => localStorage.setItem('intelligent-resume.locale', 'en-US'))
  await page.route('**/api/auth/refresh', route => route.fulfill({ json: response({ accessToken: 'e2e-token' }) }))
  await page.route('**/api/auth/me', route => route.fulfill({ json: response({ id: 1, username: 'ats-user', email: 'ats@example.com' }) }))
  await page.route('**/api/resumes', route => route.fulfill({ json: response([{ id: 1, title: 'Backend resume' }]) }))
  await page.route('**/api/resumes/1/versions**', route => route.fulfill({ json: response([{ id: 11, versionNo: 1, sourceType: 'MANUAL' }]) }))
  await page.route('**/api/jobs', route => route.fulfill({ json: response([{ id: 20, title: 'Backend Engineer', companyName: 'Example Systems' }]) }))
}

async function chooseInputs(page: Page) {
  const selects = page.locator('form select')
  await selects.nth(0).selectOption('1')
  await selects.nth(1).selectOption('11')
  await selects.nth(2).selectOption('20')
}

test('waits for AI and then reveals the combined ATS report', async ({ page }) => {
  await mockAtsPage(page)
  let reads = 0
  await page.route('**/api/ats/check', route => route.fulfill({ json: response({ ...ruleResult, analysisStatus: 'ANALYZING', aiTaskId: 88 }) }))
  await page.route('**/api/ats/checks/77', route => {
    reads += 1
    return route.fulfill({ json: response(reads > 1 ? hybridResult : { ...ruleResult, analysisStatus: 'ANALYZING', aiTaskId: 88 }) })
  })

  await page.goto('/ats')
  await chooseInputs(page)
  await page.getByRole('button', { name: 'Run AI check' }).click()

  await expect(page.getByRole('heading', { name: 'Combining rule and AI analysis' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'ATS Check Report' })).toBeVisible({ timeout: 10_000 })
  await expect(page.getByText('AI + Rules', { exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Requirement coverage' })).toBeVisible()
  await expect(page.getByText('The resume has direct Java evidence but only partial Redis evidence.')).toBeVisible()
})

test('shows consent guidance while preserving the local report', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await mockAtsPage(page)
  await page.route('**/api/ats/check', route => route.fulfill({ json: response({
    ...ruleResult,
    fallback: { code: 'CONSENT_REQUIRED', message: 'AI authorization is required.', retryable: false, consentRequired: true },
  }) }))

  await page.goto('/ats')
  await chooseInputs(page)
  await page.getByRole('button', { name: 'Run AI check' }).click()

  await expect(page.getByText('Rules fallback', { exact: true })).toBeVisible()
  await expect(page.getByText('Rule score', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Authorize AI analysis' }).click()
  await expect(page).toHaveURL(/\/ai-consent\?redirect=/)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})

test('retries a provider fallback without rerunning local rules', async ({ page }) => {
  await mockAtsPage(page)
  let postChecks = 0
  let retried = false
  await page.route('**/api/ats/check', route => {
    postChecks += 1
    return route.fulfill({ json: response({
      ...ruleResult,
      aiTaskId: 88,
      fallback: { code: 'PROVIDER_ERROR', message: 'AI is temporarily unavailable.', retryable: true, consentRequired: false },
    }) })
  })
  await page.route('**/api/ats/checks/77/ai-retry', route => {
    retried = true
    return route.fulfill({ json: response({ ...ruleResult, analysisStatus: 'ANALYZING', aiTaskId: 88 }) })
  })
  await page.route('**/api/ats/checks/77', route => route.fulfill({ json: response(retried ? hybridResult : ruleResult) }))

  await page.goto('/ats')
  await chooseInputs(page)
  await page.getByRole('button', { name: 'Run AI check' }).click()
  await page.getByRole('button', { name: 'Retry AI analysis' }).click()

  await expect(page.getByText('AI + Rules', { exact: true })).toBeVisible({ timeout: 10_000 })
  expect(postChecks).toBe(1)
})

test('runs local rules without creating an AI analysis', async ({ page }) => {
  await mockAtsPage(page)
  let requestBody: Record<string, unknown> = {}
  await page.route('**/api/ats/check', async route => {
    requestBody = route.request().postDataJSON()
    await route.fulfill({ json: response({ ...ruleResult, analysisStatus: 'RULES_ONLY' }) })
  })

  await page.goto('/ats')
  await chooseInputs(page)
  await page.getByRole('button', { name: 'Run rules only' }).click()

  await expect(page.getByText('Rules only', { exact: true })).toBeVisible()
  expect(requestBody.useAi).toBe(false)
})
