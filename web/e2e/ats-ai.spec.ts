import { expect, test, type Page } from '@playwright/test'

const response = (data: unknown) => ({ code: 0, message: 'ok', data, traceId: 'ats-e2e' })
const ruleResult = {
  id: 77,
  resumeId: 1,
  resumeVersionId: 11,
  jobDescriptionId: 20,
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
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await mockAtsPage(page)
  let reads = 0
  await page.route('**/api/ats/check', route => route.fulfill({ json: response({ ...ruleResult, resumeId: null, analysisStatus: 'ANALYZING', aiTaskId: 88 }) }))
  await page.route('**/api/ats/checks/77', route => {
    reads += 1
    return route.fulfill({ json: response(reads > 1 ? hybridResult : { ...ruleResult, resumeId: null, analysisStatus: 'ANALYZING', aiTaskId: 88 }) })
  })

  await page.goto('/ats')
  await chooseInputs(page)
  await page.getByRole('button', { name: 'Run AI check' }).click()

  await expect(page.getByRole('heading', { name: 'Combining rule and AI analysis' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Edit this section' })).toHaveCount(0)
  await expect(page.getByRole('link', { name: 'Review this evidence in editor' })).toHaveCount(0)
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
    return route.fulfill({ json: response({ ...ruleResult, resumeId: null, analysisStatus: 'ANALYZING', aiTaskId: 88 }) })
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

test('links only mapped ATS findings to the analyzed resume version', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await mockAtsPage(page)
  await page.route('**/api/ats/checks/77', route => route.fulfill({ json: response({
    ...hybridResult,
    resumeId: 5,
    resumeVersionId: 55,
    aiInsights: {
      ...hybridResult.aiInsights,
      prioritizedActions: [
        ...hybridResult.aiInsights.prioritizedActions,
        { priority: 'P2', section: 'overall', action: 'Keep the report concise', basis: 'Generic readability' },
      ],
    },
  }) }))

  await page.goto('/ats?result=77')

  await expect(page.getByRole('link', { name: 'Review this evidence in editor' })).toHaveAttribute(
    'href',
    '/resumes/5/edit?section=work&atsResultId=77&sourceVersionId=55&atsItem=evidence:0',
  )
  await expect(page.getByRole('link', { name: 'Edit this section' })).toHaveAttribute(
    'href',
    '/resumes/5/edit?section=work&atsResultId=77&sourceVersionId=55&atsItem=action:0',
  )
  await expect(page.getByText('Keep the report concise').locator('..').getByRole('link')).toHaveCount(0)
  const evidenceArticle = page.getByText('Specific technology evidence', { exact: true }).locator('..')
  const suggestionBox = await evidenceArticle.locator('small').boundingBox()
  const editorLinkBox = await evidenceArticle.getByRole('link', { name: 'Review this evidence in editor' }).boundingBox()
  expect(suggestionBox).not.toBeNull()
  expect(editorLinkBox).not.toBeNull()
  expect(editorLinkBox!.y).toBeGreaterThanOrEqual(suggestionBox!.y + suggestionBox!.height)
  await expect(page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).resolves.toBe(true)
  await page.screenshot({ path: testInfo.outputPath('ats-report-mobile-390x844.png'), fullPage: true })
})

async function mockEditorHandoff(page: Page, options: { sourceVersionId?: number; currentVersionId?: number; resultResumeId?: number; restoreFailures?: number; successorHasProvenance?: boolean; successorVersionLoadFailures?: number } = {}) {
  const sourceVersionId = options.sourceVersionId ?? 10
  let currentVersionId = options.currentVersionId ?? 11
  let restoreCalls = 0
  let restoreBody: Record<string, unknown> | null = null
  let remainingRestoreFailures = options.restoreFailures ?? 0
  let remainingSuccessorVersionLoadFailures = options.successorVersionLoadFailures ?? 0
  const loadedVersionIds: number[] = []
  await page.addInitScript(() => localStorage.setItem('intelligent-resume.locale', 'en-US'))
  await page.route('**/api/auth/refresh', route => route.fulfill({ json: response({ accessToken: 'editor-token' }) }))
  await page.route('**/api/auth/me', route => route.fulfill({ json: response({ id: 1, username: 'editor-user', email: 'editor@example.com' }) }))
  await page.route('**/api/resumes/1', route => route.fulfill({ json: response({ id: 1, title: 'Backend resume', currentVersionId, jobDescriptionId: 20 }) }))
  await page.route('**/api/ats/checks/77', route => route.fulfill({ json: response({
    ...hybridResult,
    resumeId: options.resultResumeId ?? 1,
    resumeVersionId: sourceVersionId,
  }) }))
  await page.route(new RegExp(`/api/resume-versions/(?:${sourceVersionId}|11|12)$`), route => {
    const id = Number(new URL(route.request().url()).pathname.split('/').pop())
    loadedVersionIds.push(id)
    if (id === 12 && remainingSuccessorVersionLoadFailures > 0) {
      remainingSuccessorVersionLoadFailures -= 1
      return route.fulfill({ status: 500, json: { code: 500, message: 'version load failed' } })
    }
    return route.fulfill({ json: response({
      id,
      versionNo: id === sourceVersionId ? 1 : 2,
      sourceType: id === 12 ? 'RESTORED' : 'MANUAL',
      resumeJson: {
        basics: { name: id === sourceVersionId ? 'Historical Candidate' : 'Current Candidate' },
        work: [{ company: id === sourceVersionId ? 'Historical Systems' : 'Current Systems', position: 'Engineer' }],
        education: [], skills: [], projects: [], certificates: [], awards: [], languages: [],
      },
      optimizationSummary: null,
      createdAt: '2026-08-10T09:00:00Z',
      archivedAt: null,
      restoredFromVersionId: id === 12 ? sourceVersionId : null,
      generationContext: id === 12 && options.successorHasProvenance !== false
        ? { atsProvenance: { resultId: 77, sourceVersionId, mappedSection: 'work', itemKind: 'action', itemIndex: 0 } }
        : null,
    }) })
  })
  await page.route(`**/api/resumes/1/versions/${sourceVersionId}/restore`, route => {
    restoreCalls += 1
    restoreBody = route.request().postDataJSON()
    if (remainingRestoreFailures > 0) {
      remainingRestoreFailures -= 1
      return route.fulfill({ status: 500, json: { code: 500, message: 'restore failed' } })
    }
    currentVersionId = 12
    return route.fulfill({ json: response({
      id: 12,
      versionNo: 3,
      sourceType: 'RESTORED',
      resumeJson: { basics: { name: 'Historical Candidate' }, work: [{ company: 'Historical Systems', position: 'Engineer' }] },
      optimizationSummary: `Restored from v${sourceVersionId}`,
      createdAt: '2026-08-10T09:10:00Z',
      archivedAt: null,
      restoredFromVersionId: sourceVersionId,
    }) })
  })
  return { restoreCalls: () => restoreCalls, restoreBody: () => restoreBody, loadedVersionIds: () => loadedVersionIds }
}

test('opens a historical ATS source read-only and explicitly creates an editable successor', async ({ page }) => {
  test.setTimeout(60_000)
  const calls = await mockEditorHandoff(page)
  await page.setViewportSize({ width: 390, height: 844 })

  await page.goto('/resumes/1/edit?section=work&atsResultId=77&sourceVersionId=10&atsItem=action%3A0')

  await expect(page.getByRole('navigation', { name: 'ATS report navigation' }).getByRole('link', { name: 'Return to ATS report' })).toHaveAttribute('href', '/ats?result=77')
  await expect(page.getByText('Add verified scale', { exact: true })).toHaveCount(0)
  await expect(page.locator('#resume-work')).toBeFocused()
  await expect(page.getByRole('form', { name: 'Read-only analyzed resume version' })).toHaveAttribute('aria-readonly', 'true')
  await expect(page.getByLabel('Company')).toHaveValue('Historical Systems')
  await expect(page.getByLabel('Company')).not.toBeEditable()
  await expect(page.getByRole('button', { name: 'Create editable version' })).toBeVisible()
  expect(calls.restoreCalls()).toBe(0)

  await page.getByRole('button', { name: 'Preview resume' }).click()
  await page.getByRole('button', { name: 'Back' }).click()
  await expect(page.getByLabel('Company')).not.toBeEditable()

  await page.getByRole('button', { name: 'Create editable version' }).click()

  await expect(page).toHaveURL(/sourceVersionId=10.*editVersionId=12/)
  await expect(page.getByRole('form', { name: 'Resume content editor' })).toHaveAttribute('aria-readonly', 'false')
  await expect(page.getByLabel('Company')).toBeEditable()
  expect(calls.restoreCalls()).toBe(1)
  expect(calls.restoreBody()).toEqual({ atsResultId: 77, atsItem: 'action:0' })
  await expect(page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).resolves.toBe(true)
})

test('does not unlock a normal restored current version from a forged ATS successor URL', async ({ page }) => {
  await mockEditorHandoff(page, { currentVersionId: 12, successorHasProvenance: false })

  await page.goto('/resumes/1/edit?section=work&atsResultId=77&sourceVersionId=10&atsItem=action:0&editVersionId=12')

  await expect(page.getByRole('form', { name: 'Read-only analyzed resume version' })).toHaveAttribute('aria-readonly', 'true')
  await expect(page.getByLabel('Company')).toHaveValue('Historical Systems')
  await expect(page.getByLabel('Company')).not.toBeEditable()
})

test('keeps the historical source and diagnosis return link when successor creation fails', async ({ page }) => {
  const calls = await mockEditorHandoff(page, { restoreFailures: 1 })

  await page.goto('/resumes/1/edit?section=work&atsResultId=77&sourceVersionId=10&atsItem=action:0')
  await page.getByRole('button', { name: 'Create editable version' }).click()

  await expect(page.getByRole('alert')).toHaveText('Unable to create or load a version from this history. Try again.')
  await expect(page.getByRole('navigation', { name: 'ATS report navigation' }).getByRole('link', { name: 'Return to ATS report' })).toHaveAttribute('href', '/ats?result=77')
  await expect(page.getByRole('form', { name: 'Read-only analyzed resume version' })).toHaveAttribute('aria-readonly', 'true')
  await page.getByRole('button', { name: 'Retry' }).click()

  await expect(page).toHaveURL(/editVersionId=12/)
  await expect(page.getByRole('form', { name: 'Resume content editor' })).toHaveAttribute('aria-readonly', 'false')
  expect(calls.restoreCalls()).toBe(2)
})

test('returns to the read-only source without announcing success when successor reload fails', async ({ page }) => {
  await mockEditorHandoff(page, { successorVersionLoadFailures: 1 })

  await page.goto('/resumes/1/edit?section=work&atsResultId=77&sourceVersionId=10&atsItem=action:0')
  await page.getByRole('button', { name: 'Create editable version' }).click()

  await expect(page.getByRole('alert')).toHaveText('Unable to create or load a version from this history. Try again.')
  await expect(page.getByRole('form', { name: 'Read-only analyzed resume version' })).toHaveAttribute('aria-readonly', 'true')
  await expect(page.getByRole('navigation', { name: 'ATS report navigation' })).toBeVisible()
  await expect(page.locator('.sr-only')).not.toContainText('Created an editable successor version and set it as current.')
})

test('does not unlock an unrelated current version from a hand-edited ATS URL', async ({ page }) => {
  const calls = await mockEditorHandoff(page)

  await page.goto('/resumes/1/edit?section=work&atsResultId=77&sourceVersionId=10&atsItem=action%3A0&editVersionId=11')

  await expect.poll(calls.loadedVersionIds).toEqual([11, 10])
  await expect(page.getByRole('navigation', { name: 'ATS report navigation' })).toBeVisible()
  await expect(page.getByRole('form', { name: 'Read-only analyzed resume version' })).toHaveAttribute('aria-readonly', 'true')
  await expect(page.getByLabel('Name')).toHaveValue('Historical Candidate')
  await expect(page.getByLabel('Company')).toHaveValue('Historical Systems')
  await expect(page.getByLabel('Company')).not.toBeEditable()
  await expect(page.getByRole('button', { name: 'Create editable version' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Return to ATS report' })).toHaveAttribute('href', '/ats?result=77')
})

test('keeps current-version editing and draft recovery with a return-to-diagnosis entry', async ({ page }) => {
  const calls = await mockEditorHandoff(page, { sourceVersionId: 11, currentVersionId: 11 })
  await page.addInitScript(() => localStorage.setItem('intelligent-resume.editor-draft.1.1', JSON.stringify({
    baseVersionId: 11,
    content: JSON.stringify({ basics: { name: 'Recovered Draft Candidate' }, work: [{ company: 'Draft Systems', position: 'Engineer' }] }, null, 2),
    summary: 'Recovered after ATS handoff',
    activeSection: 'basics',
    updatedAt: '2026-08-10T09:15:00Z',
  })))

  await page.goto('/resumes/1/edit?section=work&atsResultId=77&sourceVersionId=11&atsItem=action:0')

  await expect(page.getByRole('dialog', { name: 'Restore unsaved draft?' })).toBeVisible()
  await expect(page.getByRole('form', { name: 'Resume content editor' })).toHaveAttribute('aria-readonly', 'false')
  await page.getByRole('button', { name: 'Restore draft' }).click()
  await expect(page.getByLabel('Name')).toHaveValue('Recovered Draft Candidate')
  expect(calls.restoreCalls()).toBe(0)

  await expect(page.getByRole('navigation', { name: 'ATS report navigation' }).getByRole('link', { name: 'Return to ATS report' })).toHaveAttribute('href', '/ats?result=77')
})

test('confirms before replacing dirty content through a same-editor query navigation', async ({ page }) => {
  await mockEditorHandoff(page, { sourceVersionId: 11, currentVersionId: 11 })
  await page.goto('/resumes/1/edit')
  const name = page.getByLabel('Name')
  await name.fill('Unsaved Candidate')

  await page.evaluate(() => {
    window.history.pushState({}, '', '/resumes/1/edit?section=work')
  })
  await page.goBack()

  const dismissPrompt = page.waitForEvent('dialog')
  const dismissNavigation = page.goForward()
  const dismissedDialog = await dismissPrompt
  await dismissedDialog.dismiss()
  await dismissNavigation
  await expect(page).toHaveURL(/\/resumes\/1\/edit$/)
  await expect(name).toHaveValue('Unsaved Candidate')

  const acceptPrompt = page.waitForEvent('dialog')
  const acceptNavigation = page.goForward()
  const acceptedDialog = await acceptPrompt
  await acceptedDialog.accept()
  await acceptNavigation
  await expect(page).toHaveURL(/\/resumes\/1\/edit\?section=work$/)
  await expect(name).toHaveValue('Historical Candidate')
})

test('ignores an invalid ATS section and keeps the current version editable', async ({ page }) => {
  await mockEditorHandoff(page)

  await page.goto('/resumes/1/edit?section=not-a-resume-section&atsResultId=77&sourceVersionId=10&atsItem=action%3A0')

  await expect(page.getByRole('navigation', { name: 'ATS report navigation' })).toHaveCount(0)
  await expect(page.getByLabel('Name')).toHaveValue('Current Candidate')
  await expect(page.getByLabel('Name')).toBeEditable()
})

test('ignores a forged ATS item with a leading-zero index', async ({ page }) => {
  const calls = await mockEditorHandoff(page)

  await page.goto('/resumes/1/edit?section=work&atsResultId=77&sourceVersionId=10&atsItem=action:00')

  await expect(page.getByRole('navigation', { name: 'ATS report navigation' })).toHaveCount(0)
  await expect(page.getByRole('form', { name: 'Resume content editor' })).toHaveAttribute('aria-readonly', 'false')
  await expect(page.getByRole('button', { name: 'Create editable version' })).toHaveCount(0)
  expect(calls.restoreCalls()).toBe(0)
})

test('rejects an ATS handoff for a different owned resume', async ({ page }) => {
  await mockEditorHandoff(page, { resultResumeId: 99 })

  await page.goto('/resumes/1/edit?section=work&atsResultId=77&sourceVersionId=10&atsItem=action%3A0')

  await expect(page.getByRole('navigation', { name: 'ATS report navigation' })).toHaveCount(0)
  await expect(page.getByRole('form', { name: 'Resume content editor' })).toHaveAttribute('aria-readonly', 'false')
  await expect(page.getByLabel('Name')).toHaveValue('Current Candidate')
})

// ---- 002 fix plan U3/U4: same-resume ATS query change isolates stale editor actions ----

/**
 * Editor mock for the 002 context-epoch regressions. Uses the same legal
 * ATS handoff shape as mockEditorHandoff but lets the test supply a rich
 * editable current version so visible content assertions stay meaningful.
 */
async function mockEditorHandoffWithResume(page: Page, options: {
  sourceVersionId?: number
  currentVersionId?: number
  resultResumeId?: number
  resumeJson?: Record<string, unknown>
} = {}) {
  const sourceVersionId = options.sourceVersionId ?? 11
  const currentVersionId = options.currentVersionId ?? 11
  const resumeJson = options.resumeJson ?? {
    basics: { name: 'Current Candidate' },
    work: [{ company: 'Current Systems', position: 'Engineer', description: 'Built Java services.', highlights: ['Reduced P99 latency'] }],
    education: [], skills: [], projects: [], certificates: [], awards: [], languages: [],
    template: { code: 'classic' },
  }
  await page.addInitScript(() => localStorage.setItem('intelligent-resume.locale', 'en-US'))
  await page.route('**/api/auth/refresh', route => route.fulfill({ json: response({ accessToken: 'editor-token' }) }))
  await page.route('**/api/auth/me', route => route.fulfill({ json: response({ id: 1, username: 'editor-user', email: 'editor@example.com' }) }))
  await page.route('**/api/resumes/1', route => route.fulfill({ json: response({ id: 1, title: 'Backend resume', currentVersionId, jobDescriptionId: 20 }) }))
  await page.route('**/api/ats/checks/77', route => route.fulfill({ json: response({
    ...hybridResult,
    resumeId: options.resultResumeId ?? 1,
    resumeVersionId: sourceVersionId,
  }) }))
  await page.route(new RegExp(`/api/resume-versions/(?:${sourceVersionId}|11|12)$`), route => {
    const id = Number(new URL(route.request().url()).pathname.split('/').pop())
    return route.fulfill({ json: response({
      id,
      versionNo: id === sourceVersionId ? 1 : 2,
      sourceType: 'MANUAL',
      resumeJson,
      optimizationSummary: null,
      createdAt: '2026-08-10T09:00:00Z',
      archivedAt: null,
      restoredFromVersionId: null,
      generationContext: null,
    }) })
  })
}

/** Switch the editor to another legal ATS handoff on the same resume. */
async function switchAtsHandoff(page: Page, atsItem: string) {
  await page.evaluate((item) => {
    window.history.pushState({}, '', `/resumes/1/edit?section=work&atsResultId=77&sourceVersionId=11&atsItem=${item}`)
    window.dispatchEvent(new PopStateEvent('popstate'))
  }, atsItem)
}

test('does not show a stale material library failure after a same-resume ATS query change', async ({ page }) => {
  await mockEditorHandoffWithResume(page)
  let materialRequestStarted!: () => void
  let releaseMaterials!: () => void
  const started = new Promise<void>(resolve => { materialRequestStarted = resolve })
  const gate = new Promise<void>(resolve => { releaseMaterials = resolve })
  await page.route('**/api/career-materials', async route => {
    materialRequestStarted()
    await gate
    await route.fulfill({ status: 503, json: { code: 500, message: 'material library unavailable', data: null } })
  })

  await page.goto('/resumes/1/edit?section=work&atsResultId=77&sourceVersionId=11&atsItem=action%3A0')
  await page.getByRole('button', { name: 'Add from materials' }).click()
  await started

  await switchAtsHandoff(page, 'evidence%3A0')
  await expect(page).toHaveURL(/atsItem=evidence%3A0/)
  await expect(page.getByRole('button', { name: 'Add from materials' })).toBeVisible()

  releaseMaterials()
  await expect(page.getByRole('button', { name: 'Add from materials' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Loading materials…' })).toHaveCount(0)
  await expect(page.locator('.form-error')).toHaveCount(0)
})

test('does not apply a stale material insertion after a same-resume ATS query change', async ({ page }) => {
  await mockEditorHandoffWithResume(page)
  const material = { id: 88, materialType: 'WORK_EXPERIENCE', title: 'Inserted Systems', usagePreference: 'NORMAL', updatedAt: '2026-07-22T10:00:00Z' }
  await page.route('**/api/career-materials', route => route.fulfill({ json: response([material]) }))
  let insertStarted!: () => void
  let releaseInsert!: () => void
  const started = new Promise<void>(resolve => { insertStarted = resolve })
  const gate = new Promise<void>(resolve => { releaseInsert = resolve })
  await page.route('**/api/career-materials/88', async route => {
    insertStarted()
    await gate
    await route.fulfill({ json: response({ ...material, contentJson: { company: 'Inserted Systems', position: 'Architect', description: 'Led migration' }, sourceText: null, createdAt: '2026-07-22T10:00:00Z' }) })
  })

  await page.goto('/resumes/1/edit?section=work&atsResultId=77&sourceVersionId=11&atsItem=action%3A0')
  await page.getByRole('button', { name: 'Add from materials' }).click()
  await page.getByLabel('Select material').selectOption('88')
  await page.getByRole('button', { name: 'Add to this section' }).click()
  await started

  await switchAtsHandoff(page, 'evidence%3A0')
  await expect(page).toHaveURL(/atsItem=evidence%3A0/)

  releaseInsert()
  await expect(page.getByLabel('Company')).toHaveCount(1)
  await expect(page.getByLabel('Company')).toHaveValue('Current Systems')
  await expect(page.locator('#resume-work')).not.toContainText('Inserted Systems')
  await expect(page.locator('.form-error')).toHaveCount(0)
})

test('does not redirect or clear the new context from a stale save after a same-resume ATS query change', async ({ page }) => {
  await mockEditorHandoffWithResume(page)
  let saveStarted = false
  let releaseSave!: () => void
  const gate = new Promise<void>(resolve => { releaseSave = resolve })
  await page.route('**/api/resumes/1/versions**', async route => {
    if (route.request().method() === 'GET') return route.fulfill({ json: response([]) })
    saveStarted = true
    await gate
    return route.fulfill({ json: response({ id: 12, resumeId: 1, versionNo: 2, sourceType: 'MANUAL', resumeJson: {}, optimizationSummary: null, createdAt: '2026-08-10T09:10:00Z', archivedAt: null, restoredFromVersionId: null }) })
  })

  await page.goto('/resumes/1/edit?section=work&atsResultId=77&sourceVersionId=11&atsItem=action%3A0')
  // The ATS handoff auto-jumps to the work section, so reveal basics before editing.
  await page.locator('.resume-editor-navigation').getByRole('button', { name: /Personal Info/ }).click()
  await page.getByLabel('Name').fill('Dirty Candidate')
  await page.locator('.editor-save-dock').getByRole('button', { name: 'Save new version' }).click()
  await expect.poll(() => saveStarted).toBe(true)

  page.once('dialog', dialog => dialog.accept())
  await switchAtsHandoff(page, 'evidence%3A0')
  await expect(page).toHaveURL(/atsItem=evidence%3A0/)

  releaseSave()
  await expect(page).toHaveURL(/\/resumes\/1\/edit/)
  await expect(page.getByLabel('Name')).toHaveValue('Current Candidate')
  await expect(page.locator('.form-error')).toHaveCount(0)
  await expect(page.locator('.editor-save-dock')).toContainText('Content is up to date')
})

test('does not show a stale inline optimization result after a same-resume ATS query change', async ({ page }) => {
  await mockEditorHandoffWithResume(page)
  let releaseOptimize!: () => void
  const gate = new Promise<void>(resolve => { releaseOptimize = resolve })
  await page.route('**/api/ai/inline-optimize', async route => {
    await gate
    await route.fulfill({ json: response({ id: 88, taskType: 'INLINE_OPTIMIZE', status: 'RUNNING', confirmationStatus: null, errorMessage: null, resultJson: null, updatedAt: '2026-08-10T09:00:00Z' }) })
  })
  await page.route('**/api/ai/tasks/88', route => route.fulfill({ json: response({
    id: 88,
    taskType: 'INLINE_OPTIMIZE',
    status: 'SUCCESS',
    confirmationStatus: null,
    errorMessage: null,
    resultJson: {
      originalContent: 'Built Java services.',
      candidates: [{ content: 'Designed and built Java services at scale.', suggestion: 'Add scale and ownership' }],
      requiresManualConfirmation: true,
    },
    updatedAt: '2026-08-10T09:00:00Z',
  }) }))

  await page.goto('/resumes/1/edit?section=work&atsResultId=77&sourceVersionId=11&atsItem=action%3A0')
  await page.locator('#resume-work').getByRole('button', { name: 'AI optimize' }).click()
  await expect(page.locator('.ai-assistant-panel')).toBeVisible()

  await switchAtsHandoff(page, 'evidence%3A0')
  await expect(page).toHaveURL(/atsItem=evidence%3A0/)
  await expect(page.locator('.ai-assistant-panel')).toHaveCount(0)

  releaseOptimize()
  // Let the stale poll complete, then prove the result never surfaces.
  await page.waitForTimeout(2500)
  await expect(page.locator('.ai-assistant-panel')).toHaveCount(0)
  await expect(page.getByText('Designed and built Java services at scale.')).toHaveCount(0)
})
