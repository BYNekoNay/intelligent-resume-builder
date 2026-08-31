import { expect, test, type Page } from '@playwright/test'

const now = '2026-08-10T09:00:00Z'
const response = (data: unknown) => ({ code: 0, message: 'ok', data, traceId: 'home-e2e' })

const resume = {
  id: 1,
  title: 'Backend resume',
  currentVersionId: 11,
  jobDescriptionId: 20,
  createdAt: now,
  updatedAt: now,
}

const application = {
  id: 31,
  jobDescriptionId: 20,
  resumeVersionId: 11,
  status: 'INTERVIEWING',
  coverLetterText: null,
  emailBodyText: null,
  openingMessageText: null,
  feedbackText: null,
  appliedAt: now,
  version: 1,
  createdAt: now,
  updatedAt: now,
}

async function mockAuthenticatedHome(page: Page, options: {
  task?: Record<string, unknown>
  continuations?: Record<string, unknown>[]
  applications?: Record<string, unknown>[]
  resumes?: Record<string, unknown>[]
  failWorkspace?: boolean
} = {}) {
  await page.route('**/api/auth/refresh', route => route.fulfill({ json: response({ accessToken: 'home-token' }) }))
  await page.route('**/api/auth/me', route => route.fulfill({ json: response({ id: 99, username: 'home-user', email: 'home@example.com' }) }))
  await page.route('**/api/system/health', route => route.fulfill({ json: response({ status: 'UP' }) }))
  await page.route('**/api/resumes', route => options.failWorkspace
    ? route.fulfill({ status: 503, json: response(null) })
    : route.fulfill({ json: response(options.resumes ?? [resume]) }))
  await page.route('**/api/applications', route => options.failWorkspace
    ? route.fulfill({ status: 503, json: response(null) })
    : route.fulfill({ json: response(options.applications ?? []) }))
  await page.route('**/api/ai/tasks/continuations', route => route.fulfill({
    json: response(options.continuations ?? (options.task ? [options.task] : [])),
  }))
  if (options.task) {
    await page.addInitScript(() => localStorage.setItem('intelligent-resume.active-ai-task.99', '88'))
    await page.route('**/api/ai/tasks/88', route => route.fulfill({ json: response(options.task) }))
  }
}

test('prioritizes an interview before account-level generation work and keeps lower-priority work available', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await mockAuthenticatedHome(page, {
    applications: [application],
    continuations: [{
      id: 88,
      taskType: 'JOB_GENERATION',
      parentTaskId: 87,
      jobDescriptionId: 20,
      status: 'SUCCESS',
      confirmationStatus: 'PENDING',
      resultJson: {},
      errorMessage: null,
      retryCount: 0,
      resultResumeVersionId: null,
      createdAt: now,
      updatedAt: now,
    }],
  })

  await page.goto('/')
  await page.getByRole('button', { name: 'EN' }).click()

  const nextAction = page.getByRole('region', { name: 'Your next action' })
  await expect(nextAction.getByRole('heading', { name: 'Prepare for your interview' })).toBeVisible()
  await expect(nextAction.getByRole('link', { name: 'Prepare for interview' })).toHaveAttribute('href', '/interviews?jobDescriptionId=20')
  await expect(page.getByRole('link', { name: 'Prepare for interview' }).first()).toHaveAttribute('href', '/interviews?jobDescriptionId=20')
  await expect(nextAction.getByRole('link', { name: 'Continue generation' })).toHaveAttribute('href', '/generate/confirm?taskId=88')
  await expect(nextAction.getByRole('link', { name: 'Continue editing' })).toHaveAttribute('href', '/resumes/1/edit')
  await expect(page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).resolves.toBe(true)
  await page.screenshot({ path: testInfo.outputPath('home-workspace-mobile-390x844.png'), fullPage: true })
})

test('prioritizes interview preparation when no generation task is resumable', async ({ page }) => {
  await mockAuthenticatedHome(page, { applications: [application] })

  await page.goto('/')
  await page.getByRole('button', { name: 'EN' }).click()

  const nextAction = page.getByRole('region', { name: 'Your next action' })
  await expect(nextAction.getByRole('heading', { name: 'Prepare for your interview' })).toBeVisible()
  await expect(nextAction.getByRole('link', { name: 'Prepare for interview' })).toHaveAttribute('href', '/interviews?jobDescriptionId=20')
  await expect(nextAction.getByRole('link', { name: 'Continue editing' })).toBeVisible()
})

test('prioritizes a draft application before a recent resume', async ({ page }) => {
  await mockAuthenticatedHome(page, {
    applications: [{ ...application, status: 'DRAFT' }],
  })

  await page.goto('/')
  await page.getByRole('button', { name: 'EN' }).click()

  const nextAction = page.getByRole('region', { name: 'Your next action' })
  await expect(nextAction.getByRole('heading', { name: 'Finish the application draft' })).toBeVisible()
  await expect(nextAction.getByRole('link', { name: 'Continue application' })).toHaveAttribute('href', '/applications')
  await expect(page.getByRole('link', { name: 'Continue application' }).first()).toHaveAttribute('href', '/applications')
  await expect(nextAction.getByRole('link', { name: 'Continue editing' })).toBeVisible()
})

test('keeps the fixed cross-workflow order and uses task id as a stable recency tie-breaker', async ({ page }) => {
  await mockAuthenticatedHome(page, {
    applications: [
      application,
      { ...application, id: 32, status: 'DRAFT' },
    ],
    continuations: [
      {
        id: 88,
        taskType: 'JOB_GENERATION',
        parentTaskId: 87,
        jobDescriptionId: 20,
        status: 'SUCCESS',
        confirmationStatus: 'PENDING',
        resultJson: {},
        errorMessage: null,
        retryCount: 0,
        resultResumeVersionId: null,
        createdAt: now,
        updatedAt: now,
      },
      {
        id: 89,
        taskType: 'JOB_GENERATION',
        parentTaskId: 87,
        jobDescriptionId: 20,
        status: 'SUCCESS',
        confirmationStatus: 'PENDING',
        resultJson: {},
        errorMessage: null,
        retryCount: 0,
        resultResumeVersionId: null,
        createdAt: now,
        updatedAt: now,
      },
    ],
  })

  await page.goto('/')
  await page.getByRole('button', { name: 'EN' }).click()

  const nextAction = page.getByRole('region', { name: 'Your next action' })
  await expect(nextAction.getByRole('heading', { name: 'Prepare for your interview' })).toBeVisible()
  const secondaryLinks = nextAction.locator('.next-action-secondary a')
  await expect(secondaryLinks).toHaveCount(4)
  await expect(secondaryLinks.nth(0)).toHaveAttribute('href', '/applications')
  await expect(secondaryLinks.nth(1)).toHaveAttribute('href', '/generate/confirm?taskId=89')
  await expect(secondaryLinks.nth(2)).toHaveAttribute('href', '/generate/confirm?taskId=88')
  await expect(secondaryLinks.nth(3)).toHaveAttribute('href', '/resumes/1/edit')
})

test('resumes the material-selection stage at its exact task', async ({ page }) => {
  await mockAuthenticatedHome(page, {
    continuations: [{
      id: 88,
      taskType: 'JOB_MATERIAL_SELECTION',
      parentTaskId: null,
      jobDescriptionId: 20,
      status: 'SUCCESS',
      confirmationStatus: 'PENDING',
      resultJson: {},
      errorMessage: null,
      retryCount: 0,
      resultResumeVersionId: null,
      createdAt: now,
      updatedAt: now,
    }],
  })

  await page.goto('/')
  await page.getByRole('button', { name: 'EN' }).click()

  const nextAction = page.getByRole('region', { name: 'Your next action' })
  await expect(nextAction.getByRole('heading', { name: 'Confirm the selected evidence' })).toBeVisible()
  await expect(nextAction.getByRole('link', { name: 'Continue selection' })).toHaveAttribute('href', '/generate/materials?taskId=88')
})

test('keeps account-level generation work after local task memory is removed', async ({ page }) => {
  await mockAuthenticatedHome(page, {
    task: {
      id: 88,
      taskType: 'JOB_GENERATION',
      parentTaskId: 87,
      jobDescriptionId: 20,
      status: 'SUCCESS',
      confirmationStatus: 'PENDING',
      resultJson: {},
      errorMessage: null,
      retryCount: 0,
      resultResumeVersionId: null,
      createdAt: now,
      updatedAt: now,
    },
  })

  await page.goto('/')
  await page.getByRole('button', { name: 'EN' }).click()
  await expect(page.getByRole('heading', { name: 'Review the generated resume' })).toBeVisible()

  await page.getByRole('button', { name: 'Build resumes' }).click()
  await page.getByRole('group', { name: 'Build resumes' }).getByRole('link', { name: 'Manage resume versions' }).click()
  await expect(page).toHaveURL(/\/resumes$/)
  await page.evaluate(() => localStorage.removeItem('intelligent-resume.active-ai-task.99'))
  await page.getByRole('link', { name: 'ZhiLi' }).click()

  await expect(page.getByRole('heading', { name: 'Review the generated resume' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Continue editing' })).toHaveAttribute('href', '/resumes/1/edit')
})

test('shows one clear start action when there is no work to continue', async ({ page }) => {
  await mockAuthenticatedHome(page, { applications: [], resumes: [] })

  await page.goto('/')
  await page.getByRole('button', { name: 'EN' }).click()

  const nextAction = page.getByRole('region', { name: 'Your next action' })
  await expect(nextAction.getByRole('heading', { name: 'Build your first application-ready resume' })).toBeVisible()
  await expect(nextAction.getByRole('link', { name: 'Import resume' })).toHaveAttribute('href', '/resume-import')
  await expect(nextAction.getByText('Other work in progress')).toHaveCount(0)
})

test('keeps a recoverable action visible when workspace requests fail', async ({ page }) => {
  await mockAuthenticatedHome(page, { failWorkspace: true })

  await page.goto('/')
  await page.getByRole('button', { name: 'EN' }).click()

  const nextAction = page.getByRole('region', { name: 'Your next action' })
  await expect(nextAction.getByRole('heading', { name: 'Build your first application-ready resume' })).toHaveCount(0)
  await expect(nextAction.getByText('Some work status is temporarily unavailable.')).toBeVisible()
  await expect(nextAction.getByRole('button', { name: 'Reload status' })).toBeVisible()
})

test('labels the guest resume preview as an example', async ({ page }) => {
  await page.route('**/api/auth/refresh', route => route.fulfill({ status: 401, json: response(null) }))
  await page.route('**/api/system/health', route => route.fulfill({ json: response({ status: 'UP' }) }))

  await page.goto('/')
  await page.getByRole('button', { name: 'EN' }).click()

  await expect(page.getByText('Example preview', { exact: true })).toBeVisible()
  await expect(page.getByText('84%', { exact: true })).toBeVisible()
})
