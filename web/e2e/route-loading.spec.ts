import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'

const routerSource = readFileSync(
  new URL('../src/router/index.ts', import.meta.url),
  'utf8',
)

const lazyViews = [
  'ResumeListView',
  'ResumeDetailView',
  'ResumeEditorView',
  'CareerMaterialView',
  'JobDescriptionView',
  'GenerationWorkbenchView',
  'MaterialSelectionConfirmView',
  'GenerationConfirmView',
  'MatchResultView',
  'ExportView',
  'AiConsentView',
  'AtsCheckView',
  'ApplicationsView',
  'MaterialResumeGenerationView',
  'InterviewView',
  'ResumeImportView',
  'CommunicationView',
  'InterviewAssetsView',
  'AchievementGuidanceView',
  'AccountView',
] as const

const lazyRoutePaths = [
  '/resumes',
  '/resumes/1',
  '/resumes/1/edit',
  '/career-materials',
  '/generate',
  '/generate/materials?taskId=1',
  '/generate/confirm?taskId=1',
  '/jobs',
  '/ai-consent',
  '/account',
  '/ats',
  '/applications',
  '/material-generation',
  '/interviews',
  '/resume-import',
  '/communications',
  '/interview-assets',
  '/achievement-guidance',
  '/match/1',
  '/exports/1',
] as const

const response = (data: unknown) => ({ code: 0, message: 'ok', data, traceId: 'route-loading-e2e' })

async function mockAuthenticatedShell(page: Page) {
  await page.route(/^https?:\/\/[^/]+\/api\//, route => {
    const pathname = new URL(route.request().url()).pathname
    if (pathname === '/api/auth/refresh') {
      return route.fulfill({ json: response({ accessToken: 'route-loading-token' }) })
    }
    if (pathname === '/api/auth/me') {
      return route.fulfill({
        json: response({ id: 99, username: 'route-loading-user', email: 'route-loading@example.com' }),
      })
    }
    return route.fulfill({
      status: 503,
      contentType: 'application/json',
      json: { code: 503, message: 'Route-loading test does not use application services.', data: null },
    })
  })
}

async function expectRouteToResolve(page: Page, path: string) {
  await expect(page).toHaveURL(new RegExp(`${path.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`))
  await expect(page.locator('.app-shell')).toBeVisible()
  await expect(page.locator('.app-main > *')).not.toHaveCount(0)
  await expect(page.locator('vite-error-overlay')).toHaveCount(0)
}

async function navigateToAts(page: Page) {
  await page.getByRole('button', { name: 'Match & interview' }).click()
  await page.getByRole('group', { name: 'Match & interview' }).getByRole('link', { name: 'Check resume match' }).click()
}

test('loads feature views through route-level dynamic imports', () => {
  for (const view of lazyViews) {
    expect(routerSource).toContain(`import('@/views/${view}.vue')`)
    expect(routerSource).not.toMatch(new RegExp(`^import\\s+${view}\\s+from`, 'm'))
  }

  for (const eagerView of ['AppLayout', 'HomeView', 'LoginView', 'RegisterView']) {
    expect(routerSource).toMatch(new RegExp(`^import\\s+${eagerView}\\s+from`, 'm'))
  }

  expect(routerSource).toContain('router.onError')
  expect(routerSource).toContain('sessionStorage')
})

for (const path of lazyRoutePaths) {
  test(`resolves and refreshes lazy route ${path}`, async ({ page }) => {
    test.setTimeout(60_000)
    await mockAuthenticatedShell(page)

    await page.goto(path, { waitUntil: 'domcontentloaded' })
    await expectRouteToResolve(page, path)

    await page.reload({ waitUntil: 'domcontentloaded' })
    await expectRouteToResolve(page, path)
  })
}

test('keeps the full lazy-route destination when authentication redirects', async ({ page }) => {
  await page.route('**/api/auth/refresh', route => route.fulfill({ status: 401, json: response(null) }))

  const destination = '/resumes/7/edit?section=work&atsResultId=9'
  await page.goto(destination)

  await expect(page).toHaveURL(/\/login\?redirect=/)
  expect(new URL(page.url()).searchParams.get('redirect')).toBe(destination)
})

test('recovers once from a stale lazy chunk and clears the retry marker after a successful reload', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('intelligent-resume.locale', 'en-US'))
  await mockAuthenticatedShell(page)
  let importRequests = 0
  let documentLoads = 0
  page.on('load', () => { documentLoads += 1 })
  await page.route('**/src/views/AtsCheckView.vue*', route => {
    importRequests += 1
    return importRequests === 1 ? route.abort('failed') : route.continue()
  })

  await page.goto('/')
  const recoveredDocument = page.waitForEvent('framenavigated', frame => frame === page.mainFrame()
    && new URL(frame.url()).pathname === '/ats')
  await navigateToAts(page)
  await recoveredDocument

  await expect(page).toHaveURL(/\/ats$/)
  await expect(page.getByRole('heading', { name: 'ATS Smart Check' })).toBeVisible()
  expect(importRequests).toBeGreaterThanOrEqual(2)
  expect(documentLoads).toBe(2)
  await expect.poll(() => page.evaluate(() => Object.keys(sessionStorage)
    .filter(key => key.startsWith('intelligent-resume.lazy-chunk-retry:')))).toEqual([])
})

test('does not reload repeatedly when a lazy chunk remains unavailable', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('intelligent-resume.locale', 'en-US'))
  await mockAuthenticatedShell(page)
  let importRequests = 0
  let documentLoads = 0
  page.on('load', () => { documentLoads += 1 })
  await page.route('**/src/views/AtsCheckView.vue*', route => {
    importRequests += 1
    return route.abort('failed')
  })

  await page.goto('/')
  const recoveredDocument = page.waitForEvent('framenavigated', frame => frame === page.mainFrame()
    && new URL(frame.url()).pathname === '/ats')
  await navigateToAts(page)
  await recoveredDocument

  await expect.poll(() => importRequests).toBeGreaterThanOrEqual(2)
  await page.waitForTimeout(500)
  expect(documentLoads).toBe(2)
  await expect(page).toHaveURL(/\/route-load-error\?retry=\/?ats$/)
  await expect(page.getByRole('heading', { name: 'This page could not load' })).toBeVisible()
  await expect.poll(() => page.evaluate(() => Object.keys(sessionStorage)
    .filter(key => key.startsWith('intelligent-resume.lazy-chunk-retry:')))).toHaveLength(1)
})
