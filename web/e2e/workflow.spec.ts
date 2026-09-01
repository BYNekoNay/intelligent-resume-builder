import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'

const now = '2026-07-22T10:00:00Z'
const response = (data: unknown) => ({ code: 0, message: 'ok', data, traceId: 'e2e' })
const materialSearch = (items: Array<Record<string, unknown>>, overrides: Record<string, unknown> = {}) => ({
  items: items.map(item => ({ excerpt: '', ...item })),
  page: 0,
  size: 25,
  totalElements: items.length,
  totalPages: items.length ? 1 : 0,
  typeCounts: items.reduce<Record<string, number>>((counts, item) => {
    const type = String(item.materialType)
    counts[type] = (counts[type] ?? 0) + 1
    return counts
  }, {}),
  ...overrides,
})
const resume = { id: 1, title: 'Backend resume', currentVersionId: 11, jobDescriptionId: null, createdAt: now, updatedAt: now }
const version = { id: 11, resumeId: 1, versionNo: 1, sourceType: 'MANUAL', resumeJson: {}, optimizationSummary: null, createdAt: now }
const job = { id: 20, title: 'Backend Engineer', companyName: 'Example Systems', jdText: 'Java and Spring Boot', parsedKeywordsJson: null, parsedAt: null, parsedVersion: null, createdAt: now, updatedAt: now }
const dimensionScores = {
  relevance: 20,
  evidenceSpecificity: 18,
  structureClarity: 16,
  roleCompetency: 15,
  authenticityReflection: 8,
}

function interviewState(interviewId: number, overrides: Record<string, unknown> = {}) {
  return {
    interviewId,
    status: 'AWAITING_ANSWER',
    executionMode: 'AI',
    currentQuestion: 'Question one',
    currentQuestionNo: 1,
    completedQuestionCount: 0,
    targetQuestionCount: 6,
    minQuestionCount: 3,
    maxQuestionCount: 9,
    lastEvaluation: null,
    aiFailure: null,
    completionReason: null,
    ...overrides,
  }
}

function lastEvaluation(roundNo: number, questionText: string, answerText: string) {
  return {
    recordId: 100 + roundNo,
    roundNo,
    questionText,
    answerText,
    roundScore: 77,
    dimensionScores,
    evaluationSource: 'AI',
    strengths: ['Clear evidence'],
    improvements: ['Add context'],
    suggestedAnswer: 'A more structured answer.',
  }
}
const allSectionsResume = JSON.parse(readFileSync(
  new URL('../../test-fixtures/resume-all-sections.json', import.meta.url),
  'utf8',
)) as Record<string, unknown>

async function mockAuthenticatedApi(page: Page) {
  await page.route('**/api/auth/refresh', route => route.fulfill({ json: response({ accessToken: 'e2e-token' }) }))
  await page.route('**/api/auth/me', route => route.fulfill({ json: response({ id: 99, username: 'e2e-user', email: 'e2e@example.com' }) }))
  await page.route('**/api/resumes', route => route.fulfill({ json: response([resume]) }))
  await page.route('**/api/resumes/1', route => route.fulfill({ json: response(resume) }))
  await page.route('**/api/resumes/1/versions**', route => route.fulfill({ json: response([version]) }))
  await page.route('**/api/jobs', route => route.fulfill({ json: response([job]) }))
  await page.route('**/api/career-materials/search*', route => route.fulfill({ json: response(materialSearch([])) }))
  await page.route('**/api/career-materials*', route => route.fulfill({ json: response([]) }))
  await page.route('**/api/personal-profile*', route => route.fulfill({ json: response({ fullName: '', email: '', phone: '', location: '', website: '', profileSummary: '' }) }))
  await page.route('**/api/applications', route => route.fulfill({ json: response([]) }))
  await page.route('**/api/interview-answer-assets**', route => route.fulfill({ json: response([]) }))
}

test('confirms before starting a general interview without a selected job', async ({ page }) => {
  await mockAuthenticatedApi(page)
  const payloads: unknown[] = []
  await page.route('**/api/interviews/start', async route => {
    payloads.push(route.request().postDataJSON())
    await route.fulfill({ json: response(interviewState(51, { currentQuestion: 'Tell me about a technical project.' })) })
  })
  await page.goto('/interviews')
  await page.locator('.interview-setup textarea').fill('Java engineer with five years of experience.')

  page.once('dialog', dialog => dialog.dismiss())
  await page.locator('.interview-setup > button').click()
  await expect(page.locator('.interview-setup')).toBeVisible()
  expect(payloads).toHaveLength(0)

  page.once('dialog', dialog => dialog.accept())
  await page.locator('.interview-setup > button').click()
  await expect(page.getByText('Tell me about a technical project.')).toBeVisible()
  expect(payloads).toEqual([{
    sourceType: 'EXTERNAL_RESUME',
    externalResumeText: 'Java engineer with five years of experience.',
    interviewMode: 'TECHNICAL',
    targetQuestionCount: 6,
    outputLanguage: 'ZH_CN',
  }])
})

test('starts a selected-job interview without a confirmation prompt', async ({ page }) => {
  await mockAuthenticatedApi(page)
  let payload: unknown = null
  let dialogOpened = false
  page.on('dialog', async dialog => { dialogOpened = true; await dialog.dismiss() })
  await page.route('**/api/interviews/start', async route => {
    payload = route.request().postDataJSON()
    await route.fulfill({ json: response(interviewState(52, { currentQuestion: 'Why this role?' })) })
  })
  await page.goto('/interviews')
  await page.locator('.interview-setup textarea').fill('Java engineer with five years of experience.')
  await page.locator('.interview-setup select').nth(2).selectOption('20')
  await page.locator('.interview-setup > button').click()

  await expect(page.getByText('Why this role?')).toBeVisible()
  expect(dialogOpened).toBe(false)
  expect(payload).toEqual({
    sourceType: 'EXTERNAL_RESUME',
    externalResumeText: 'Java engineer with five years of experience.',
    jobDescriptionId: 20,
    interviewMode: 'TECHNICAL',
    targetQuestionCount: 6,
    outputLanguage: 'ZH_CN',
  })
})

test('keeps the interview open for three rounds and then shows the complete report', async ({ page }) => {
  await mockAuthenticatedApi(page)
  let round = 0
  await page.route('**/api/interviews/start', route => route.fulfill({
    json: response(interviewState(53)),
  }))
  await page.route('**/api/interviews/53/answer', async route => {
    round += 1
    const questions = ['Question two', 'Question three', null]
    const completed = round === 3
    await route.fulfill({ json: response(interviewState(53, {
      status: completed ? 'COMPLETED' : 'AWAITING_ANSWER',
      currentQuestion: questions[round - 1],
      currentQuestionNo: completed ? null : round + 1,
      completedQuestionCount: round,
      lastEvaluation: lastEvaluation(round, `Question ${round}`, 'A detailed answer with measurable results.'),
      completionReason: completed ? 'AI_INFORMATION_COMPLETE' : null,
    })) })
  })
  await page.route('**/api/interviews/53/report', route => route.fulfill({ json: response({
    totalScore: 80, summary: 'Completed 3 rounds.', strengths: ['Clear evidence'],
    weaknesses: ['Add context'], resumeSuggestions: ['Add the result to the resume.'],
    expressionSuggestions: ['Lead with the conclusion.'],
    dimensionScores, targetQuestionCount: 6, actualQuestionCount: 3,
    completionReason: 'AI_INFORMATION_COMPLETE', evaluationSource: 'AI',
    aiEvaluatedRounds: 3, ruleEvaluatedRounds: 0,
  }) }))

  await page.goto('/interviews')
  await page.locator('.interview-setup textarea').fill('Java engineer with five years of experience.')
  page.once('dialog', dialog => dialog.accept())
  await page.locator('.interview-setup > button').click()

  for (const expectedQuestion of ['Question two', 'Question three']) {
    await page.getByRole('textbox', { name: '你的回答' }).fill('A detailed answer with measurable results.')
    await page.getByRole('button', { name: '提交回答' }).click()
    await expect(page.getByRole('heading', { name: expectedQuestion })).toBeVisible()
    await expect(page.getByRole('button', { name: '提交回答' })).toBeVisible()
  }

  await page.getByRole('textbox', { name: '你的回答' }).fill('A final detailed answer with measurable results.')
  await page.getByRole('button', { name: '提交回答' }).click()
  await expect(page.getByRole('heading', { name: '面试完成。请查看报告或保存答案资产。' })).toBeVisible()
  await expect(page.getByRole('textbox', { name: '你的回答' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '提交回答' })).toHaveCount(0)

  await page.getByRole('button', { name: '面试报告' }).click()
  const report = page.locator('.interview-report')
  await expect(report.getByText('Completed 3 rounds.')).toBeVisible()
  await expect(report.getByText('Clear evidence')).toBeVisible()
  await expect(report.getByText('Lead with the conclusion.')).toBeVisible()
  expect(round).toBe(3)
})

test('retries a failed AI operation and can continue in rule mode', async ({ page }) => {
  await mockAuthenticatedApi(page)
  const failure = {
    operationId: 301,
    stage: 'GENERATE_QUESTION',
    retryable: true,
    reauthorizationRequired: false,
    messageCode: 'AI_PROVIDER_UNAVAILABLE',
  }
  await page.route('**/api/interviews/start', route => route.fulfill({
    json: response(interviewState(54, {
      status: 'AI_ACTION_REQUIRED', currentQuestion: null, currentQuestionNo: null, aiFailure: failure,
    })),
  }))
  await page.route('**/api/interviews/54/ai/retry', route => route.fulfill({
    json: response(interviewState(54, {
      status: 'AI_ACTION_REQUIRED', currentQuestion: null, currentQuestionNo: null,
      aiFailure: { ...failure, retryable: false },
    })),
  }))
  await page.route('**/api/interviews/54/continue-with-rules', route => route.fulfill({
    json: response(interviewState(54, { executionMode: 'RULE', currentQuestion: 'Rule fallback question' })),
  }))

  await page.goto('/interviews')
  await page.locator('.interview-setup textarea').fill('Java engineer with five years of experience.')
  page.once('dialog', dialog => dialog.accept())
  await page.getByRole('button', { name: '开始面试' }).click()
  await expect(page.getByText('AI 服务暂时不可用')).toBeVisible()

  await page.getByRole('button', { name: '重试 AI' }).click()
  await expect(page.getByRole('button', { name: '重试 AI' })).toHaveCount(0)
  await page.getByRole('button', { name: '切换到规则模式' }).click()
  await expect(page.getByRole('heading', { name: 'Rule fallback question' })).toBeVisible()
  await expect(page.getByText('规则模式', { exact: true })).toBeVisible()
})

test('restores an unfinished interview after a page refresh', async ({ page }) => {
  await mockAuthenticatedApi(page)
  let stateRequests = 0
  await page.addInitScript(() => sessionStorage.setItem('interview-session-id', '55'))
  await page.route('**/api/interviews/55', route => {
    stateRequests += 1
    return route.fulfill({ json: response(interviewState(55, { currentQuestion: 'Recovered question' })) })
  })

  await page.goto('/interviews')
  await expect(page.getByRole('heading', { name: 'Recovered question' })).toBeVisible()
  expect(stateRequests).toBe(1)
})

test('polls an in-flight interview until the server finishes processing', async ({ page }) => {
  await mockAuthenticatedApi(page)
  let stateRequests = 0
  await page.addInitScript(() => sessionStorage.setItem('interview-session-id', '57'))
  await page.route('**/api/interviews/57', route => {
    stateRequests += 1
    const state = stateRequests === 1
      ? interviewState(57, { status: 'EVALUATING_ANSWER', currentQuestion: 'Question one' })
      : interviewState(57, { currentQuestion: 'Recovered next question', completedQuestionCount: 1 })
    return route.fulfill({ json: response(state) })
  })

  await page.goto('/interviews')
  await expect(page.getByText('AI 正在评估你的回答')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Recovered next question' })).toBeVisible({ timeout: 5000 })
  expect(stateRequests).toBeGreaterThanOrEqual(2)
})

test('restores a completed interview and its report after refresh', async ({ page }) => {
  await mockAuthenticatedApi(page)
  await page.addInitScript(() => sessionStorage.setItem('interview-session-id', '58'))
  await page.route('**/api/interviews/58', route => route.fulfill({ json: response(interviewState(58, {
    status: 'COMPLETED', currentQuestion: null, currentQuestionNo: null,
    completedQuestionCount: 3, completionReason: 'AI_INFORMATION_COMPLETE',
  })) }))
  await page.route('**/api/interviews/58/report', route => route.fulfill({ json: response({
    totalScore: 81, summary: 'Recovered completed report.', strengths: [], weaknesses: [],
    resumeSuggestions: [], expressionSuggestions: [], dimensionScores,
    targetQuestionCount: 6, actualQuestionCount: 3, completionReason: 'AI_INFORMATION_COMPLETE',
    evaluationSource: 'AI', aiEvaluatedRounds: 3, ruleEvaluatedRounds: 0,
  }) }))

  await page.goto('/interviews')
  await expect(page.locator('.interview-report').getByText('Recovered completed report.')).toBeVisible()
  await expect.poll(() => page.evaluate(() => sessionStorage.getItem('interview-session-id'))).toBe('58')
})

test('preserves a failed answer when switching to rule fallback', async ({ page }) => {
  await mockAuthenticatedApi(page)
  const answerText = 'I used a staged rollout and reduced incidents by 30 percent.'
  await page.route('**/api/interviews/start', route => route.fulfill({ json: response(interviewState(59)) }))
  await page.route('**/api/interviews/59/answer', route => route.fulfill({ json: response(interviewState(59, {
    status: 'AI_ACTION_REQUIRED', aiFailure: {
      operationId: 401, stage: 'ANSWER_EVALUATION', retryable: true,
      reauthorizationRequired: false, messageCode: 'AI_FAILURE',
    },
  })) }))
  await page.route('**/api/interviews/59/continue-with-rules', route => route.fulfill({ json: response(interviewState(59, {
    executionMode: 'RULE', currentQuestion: 'Question one',
  })) }))

  await page.goto('/interviews')
  await page.locator('.interview-setup textarea').fill('Java engineer with five years of experience.')
  page.once('dialog', dialog => dialog.accept())
  await page.getByRole('button', { name: '开始面试' }).click()
  await page.getByRole('textbox', { name: '你的回答' }).fill(answerText)
  await page.getByRole('button', { name: '提交回答' }).click()
  await page.getByRole('button', { name: '切换到规则模式' }).click()

  await expect(page.getByRole('textbox', { name: '你的回答' })).toHaveValue(answerText)
})

test('saves the evaluated question and answer snapshot as an asset', async ({ page }) => {
  await mockAuthenticatedApi(page)
  let assetPayload: unknown = null
  const originalQuestion = 'Describe a production incident you resolved.'
  const originalAnswer = 'I traced the latency to a saturated connection pool and reduced P99 by 35%.'
  await page.route('**/api/interviews/start', route => route.fulfill({
    json: response(interviewState(56, { currentQuestion: originalQuestion })),
  }))
  await page.route('**/api/interviews/56/answer', route => route.fulfill({
    json: response(interviewState(56, {
      currentQuestion: 'What did you learn from that incident?', currentQuestionNo: 2,
      completedQuestionCount: 1, lastEvaluation: lastEvaluation(1, originalQuestion, originalAnswer),
    })),
  }))
  await page.route('**/api/interview-answer-assets', async route => {
    assetPayload = route.request().postDataJSON()
    await route.fulfill({ json: response({ id: 501 }) })
  })

  await page.goto('/interviews')
  await page.locator('.interview-setup textarea').fill('Java engineer with five years of experience.')
  page.once('dialog', dialog => dialog.accept())
  await page.getByRole('button', { name: '开始面试' }).click()
  await page.getByRole('textbox', { name: '你的回答' }).fill(originalAnswer)
  await page.getByRole('button', { name: '提交回答' }).click()
  await page.getByRole('button', { name: '保存为答案资产' }).click()

  await expect.poll(() => assetPayload).toEqual({
    interviewRecordId: 101,
    questionText: originalQuestion,
    originalAnswerText: originalAnswer,
    suggestedAnswerText: 'A more structured answer.',
  })
  await expect(page.getByText('已保存', { exact: true })).toBeVisible()
})

test('reuses the start idempotency key when the same request is retried', async ({ page }) => {
  await mockAuthenticatedApi(page)
  const keys: string[] = []
  let attempt = 0
  await page.route('**/api/interviews/start', async route => {
    attempt += 1
    keys.push(route.request().headers()['idempotency-key'])
    if (attempt === 1) {
      await route.fulfill({ status: 500, json: { code: 50000, message: 'temporary failure', data: null, traceId: 'e2e' } })
      return
    }
    await route.fulfill({ json: response(interviewState(57, { currentQuestion: 'Idempotent question' })) })
  })

  await page.goto('/interviews')
  await page.locator('.interview-setup textarea').fill('Java engineer with five years of experience.')
  page.once('dialog', dialog => dialog.accept())
  await page.getByRole('button', { name: '开始面试' }).click()
  await expect(page.getByRole('alert')).toContainText('无法启动面试')

  page.once('dialog', dialog => dialog.accept())
  await page.getByRole('button', { name: '开始面试' }).click()
  await expect(page.getByRole('heading', { name: 'Idempotent question' })).toBeVisible()
  expect(keys).toHaveLength(2)
  expect(keys[0]).toBeTruthy()
  expect(keys[1]).toBe(keys[0])
})

test('requires confirmation before ending an interview early', async ({ page }) => {
  await mockAuthenticatedApi(page)
  let finishCalls = 0
  await page.route('**/api/interviews/start', route => route.fulfill({
    json: response(interviewState(58, { completedQuestionCount: 1, currentQuestionNo: 2 })),
  }))
  await page.route('**/api/interviews/58/finish', route => {
    finishCalls += 1
    return route.fulfill({ json: response(interviewState(58, {
      status: 'COMPLETED', currentQuestion: null, currentQuestionNo: null,
      completedQuestionCount: 1, completionReason: 'USER_FINISHED',
    })) })
  })

  await page.goto('/interviews')
  await page.locator('.interview-setup textarea').fill('Java engineer with five years of experience.')
  page.once('dialog', dialog => dialog.accept())
  await page.getByRole('button', { name: '开始面试' }).click()

  page.once('dialog', dialog => dialog.dismiss())
  await page.getByRole('button', { name: '结束面试' }).click()
  expect(finishCalls).toBe(0)
  page.once('dialog', dialog => dialog.accept())
  await page.getByRole('button', { name: '结束面试' }).click()
  await expect(page.getByRole('heading', { name: '面试完成。请查看报告或保存答案资产。' })).toBeVisible()
  expect(finishCalls).toBe(1)
})

test('redirects to AI consent when interview authorization is missing', async ({ page }) => {
  await mockAuthenticatedApi(page)
  await page.route('**/api/ai/consent', route => route.fulfill({ json: response(null) }))
  await page.route('**/api/interviews/start', route => route.fulfill({
    status: 403,
    json: { code: 40301, message: 'consent required', data: null, traceId: 'e2e' },
  }))

  await page.goto('/interviews')
  await page.locator('.interview-setup textarea').fill('Java engineer with five years of experience.')
  page.once('dialog', dialog => dialog.accept())
  await page.getByRole('button', { name: '开始面试' }).click()

  await expect(page).toHaveURL(/\/ai-consent\?redirect=\/interviews$/)
  await expect(page.getByRole('heading', { name: '管理 AI 数据授权' })).toBeVisible()
})

test('keeps the active interview usable on a mobile viewport', async ({ page }) => {
  await mockAuthenticatedApi(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.route('**/api/interviews/start', route => route.fulfill({
    json: response(interviewState(59, { currentQuestion: 'Mobile interview question' })),
  }))

  await page.goto('/interviews')
  await page.locator('.interview-setup textarea').fill('Java engineer with five years of experience.')
  await page.locator('.interview-setup select').nth(2).selectOption('20')
  await page.getByRole('button', { name: '开始面试' }).click()

  await expect(page.getByRole('heading', { name: 'Mobile interview question' })).toBeVisible()
  await expect(page.getByRole('textbox', { name: '你的回答' })).toBeVisible()
  await expect(page.getByRole('button', { name: '提交回答' })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBe(0)
})

test('takes an authenticated user from the home primary action to the latest resume editor', async ({ page }) => {
  await mockAuthenticatedApi(page)

  await page.goto('/')
  await expect(page.getByRole('heading', { name: /更有把握的下一次投递/ })).toBeVisible()
  await expect(page.getByRole('region', { name: '你的下一步' }).getByRole('heading', { name: '继续完善最近的简历' })).toBeVisible()
  await expect(page.getByText('示例预览', { exact: true })).toHaveCount(0)
  await expect(page.locator('.home-primary-action')).toHaveText(/继续编辑/)
  await page.locator('.home-primary-action').click()

  await expect(page).toHaveURL(/\/resumes\/1\/edit$/)
})

test('keeps the complete product navigation usable on mobile', async ({ page }) => {
  await mockAuthenticatedApi(page)
  await page.setViewportSize({ width: 390, height: 844 })

  await page.goto('/')
  await page.getByRole('button', { name: '打开导航菜单' }).click()
  const mobileNavigation = page.locator('#mobile-navigation')
  await expect(mobileNavigation).toBeVisible()
  await expect(mobileNavigation.getByRole('link', { name: '管理简历版本' })).toBeVisible()
  await mobileNavigation.getByRole('link', { name: '管理简历版本' }).click()

  await expect(page).toHaveURL(/\/resumes$/)
  await expect(mobileNavigation).toHaveCount(0)
})

test('uses task-oriented navigation and keeps deep resume routes in their active group', async ({ page }) => {
  await mockAuthenticatedApi(page)

  await page.goto('/resumes/1/edit')
  await page.getByRole('button', { name: 'EN' }).click()

  const resumeGroup = page.getByRole('button', { name: 'Build resumes' })
  await expect(resumeGroup).toHaveClass(/active/)
  await resumeGroup.click()

  const menu = page.getByRole('group', { name: 'Build resumes' })
  await expect(menu.getByRole('link', { name: /Tailor to a job description/ })).toHaveAttribute('href', '/generate')
  await expect(menu.getByText('Select evidence against a target role, then review every change.')).toBeVisible()
  await expect(menu.getByRole('link', { name: /Assemble from career materials/ })).toHaveAttribute('href', '/material-generation')
  await expect(menu.getByText('Build a general resume from your verified career records.')).toBeVisible()

  await page.keyboard.press('Escape')
  await expect(resumeGroup).toHaveAttribute('aria-expanded', 'false')
  await resumeGroup.focus()
  await page.keyboard.press('Enter')
  await page.keyboard.press('Tab')
  await expect(page.locator('.nav-group-menu a[href="/resumes"]')).toBeFocused()
})

test('exposes the same task routes in desktop and mobile navigation', async ({ page }) => {
  await mockAuthenticatedApi(page)
  await page.goto('/')
  await page.getByRole('button', { name: 'EN' }).click()

  const desktopRoutes: string[] = []
  for (const label of ['Prepare evidence', 'Build resumes', 'Match & interview', 'Apply & follow up']) {
    const trigger = page.getByRole('button', { name: label })
    await trigger.click()
    desktopRoutes.push(...await page.getByRole('group', { name: label }).getByRole('link').evaluateAll(links => links.map(link => link.getAttribute('href') ?? '')))
    await trigger.click()
  }

  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByRole('button', { name: 'Open navigation menu' }).click()
  const mobileNavigation = page.locator('#mobile-navigation')
  const mobileRoutes = await mobileNavigation.getByRole('link').evaluateAll(links => links
    .map(link => link.getAttribute('href') ?? '')
    .filter(href => !['/account', '/ai-consent'].includes(href)))

  expect([...desktopRoutes].sort()).toEqual([...mobileRoutes].sort())
  await expect(mobileNavigation.getByRole('link', { name: /Tailor to a job description/ })).toBeVisible()
  await expect(mobileNavigation.getByRole('link', { name: /Assemble from career materials/ })).toBeVisible()
})

test('keeps application evidence visible while tracking a pipeline stage', async ({ page }) => {
  await mockAuthenticatedApi(page)
  const application = {
    id: 31,
    jobDescriptionId: 20,
    resumeVersionId: 11,
    status: 'APPLIED',
    coverLetterText: 'Evidence-backed cover letter',
    emailBodyText: null,
    openingMessageText: null,
    feedbackText: 'Recruiter replied',
    appliedAt: now,
    version: 2,
    createdAt: now,
    updatedAt: now,
  }
  let statusPayload: unknown = null
  // 宽泛 applications** 覆盖 /api/applications?followUp=ALL 的 query 形态；
  // /stats 单独注册（后注册优先命中）。
  await page.route('**/api/applications**', route => route.fulfill({ json: response([application]) }))
  await page.route('**/api/applications/stats', route => route.fulfill({ json: response({
    total: 1,
    byStatus: [
      { status: 'DRAFT', count: 0, percent: 0 },
      { status: 'APPLIED', count: 1, percent: 100 },
      { status: 'INTERVIEWING', count: 0, percent: 0 },
      { status: 'OFFERED', count: 0, percent: 0 },
      { status: 'REJECTED', count: 0, percent: 0 },
      { status: 'WITHDRAWN', count: 0, percent: 0 },
    ],
    conversionRates: { appliedToInterviewing: 0, interviewingToOffered: null, appliedToOffered: 0 },
    avgStageDurationDays: { applied: 0, interviewing: null, totalToOffer: null },
  }) }))
  await page.route('**/api/applications/31/status', async route => {
    statusPayload = route.request().postDataJSON()
    await route.fulfill({ json: response({ ...application, status: 'INTERVIEWING', version: 3 }) })
  })

  await page.goto('/applications')
  await expect(page.getByRole('heading', { name: '投递记录', exact: true })).toBeVisible()
  await expect(page.getByText('Example Systems', { exact: true })).toBeVisible()
  await expect(page.getByText('Backend Engineer', { exact: true })).toBeVisible()
  await expect(page.locator('.pipeline-lane')).toHaveCount(6)

  const ticket = page.locator('.application-ticket')
  await expect(ticket).toHaveCount(1)
  const stage = ticket.getByLabel('投递阶段')
  await stage.selectOption('INTERVIEWING')
  await expect.poll(() => statusPayload).toEqual({ status: 'INTERVIEWING', version: 2, feedbackText: 'Recruiter replied' })
  await expect(page.locator('.lane-interviewing .application-ticket')).toHaveCount(1)

  const search = page.locator('.pipeline-search input')
  await search.fill('No matching role')
  await expect(page.locator('.application-ticket')).toHaveCount(0)

  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.locator('.application-board')).toBeVisible()
  expect(await page.locator('.application-board').evaluate(element => element.scrollWidth > element.clientWidth)).toBe(true)
})

test('keeps authentication forms clear and lets users inspect their password', async ({ page }) => {
  await page.route('**/api/auth/refresh', route => route.fulfill({ status: 401, json: response(null) }))

  await page.goto('/login')
  await expect(page.getByRole('heading', { name: '登录', exact: true })).toBeVisible()
  const password = page.getByLabel('密码', { exact: true })
  await password.fill('visible-pass')
  await expect(password).toHaveAttribute('type', 'password')
  await page.getByRole('button', { name: '显示密码' }).click()
  await expect(password).toHaveAttribute('type', 'text')

  await page.getByRole('link', { name: '创建账户' }).click()
  await expect(page.getByRole('heading', { name: '创建账户', exact: true })).toBeVisible()
  await expect(page.getByText('至少 8 位字符。')).toBeVisible()
})

test('organizes account identity, security, and AI consent without hiding actions', async ({ page }) => {
  await mockAuthenticatedApi(page)

  await page.goto('/account')
  await expect(page.getByRole('heading', { name: '账户与个人资料' })).toBeVisible()
  await expect(page.getByText('@e2e-user')).toBeVisible()
  await expect(page.getByRole('button', { name: '修改邮箱' })).toBeVisible()
  await expect(page.getByRole('link', { name: '管理 AI 数据授权' })).toBeVisible()

  await page.getByRole('button', { name: '修改邮箱' }).click()
  const dialog = page.getByRole('dialog', { name: '修改邮箱' })
  await expect(dialog).toBeVisible()
  await expect(dialog.getByLabel('新邮箱')).toHaveValue('e2e@example.com')
  await dialog.getByRole('button', { name: '关闭' }).click()
  await expect(dialog).toHaveCount(0)
})

test('gives resume creation, import, and saved resumes a clear action hierarchy', async ({ page }) => {
  await mockAuthenticatedApi(page)

  await page.goto('/resumes')
  await expect(page.getByRole('heading', { name: '管理你的简历版本' })).toBeVisible()
  await expect(page.getByRole('link', { name: '导入已有简历' })).toHaveAttribute('href', '/resume-import')
  await expect(page.getByRole('heading', { name: '创建一份基础简历' })).toBeVisible()
  await expect(page.getByRole('button', { name: /新建简历/ })).toBeVisible()
  await expect(page.getByRole('link', { name: /Backend resume/ })).toContainText('2026年7月22日')
  await expect(page.getByRole('button', { name: '删除 Backend resume' })).toBeVisible()
})

test('turns resume import into a file, parse, and review sequence', async ({ page }) => {
  await mockAuthenticatedApi(page)
  await page.route('**/api/resume-imports/parse', route => route.fulfill({ json: response({
    fileName: 'existing-resume.txt',
    mediaType: 'text/plain',
    extractedText: '张明远\n高级后端工程师\nSpring Boot',
    normalizedResumeInput: { basics: { name: '张明远' }, skills: ['Spring Boot'] },
    originalFileStored: false,
  }) }))

  await page.goto('/resume-import')
  await expect(page.getByRole('heading', { name: '导入并整理已有简历' })).toBeVisible()
  await page.locator('input[type="file"]').setInputFiles({
    name: 'existing-resume.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('张明远\n高级后端工程师\nSpring Boot'),
  })
  await expect(page.getByText('existing-resume.txt')).toBeVisible()
  await page.getByRole('button', { name: '解析文件内容' }).click()

  await expect(page.getByRole('heading', { name: '检查提取出的内容' })).toBeVisible()
  await expect(page.getByLabel('可编辑文本')).toHaveValue(/Spring Boot/)
  await expect(page.getByText('源文件已保存: 否')).toBeVisible()
  await expect(page.getByRole('button', { name: /确认文本并继续/ })).toBeVisible()
})

test('renders the material workspace as three independent desktop columns', async ({ page }, testInfo) => {
  await mockAuthenticatedApi(page)
  await page.setViewportSize({ width: 1440, height: 900 })
  const summary = { id: 88, materialType: 'PROJECT_EXPERIENCE', title: 'Platform migration', usagePreference: 'PREFERRED', updatedAt: now }
  await page.route('**/api/career-materials/search*', route => route.fulfill({ json: response(materialSearch([summary])) }))
  await page.route('**/api/career-materials/88', route => route.fulfill({ json: response({
    ...summary, contentJson: { outcome: 'Zero-downtime migration.' }, sourceText: 'Moved the platform without downtime.', createdAt: now,
  }) }))

  await page.goto('/career-materials')
  await page.getByRole('article', { name: summary.title }).locator('.row-select').click()
  const navigation = page.locator('.material-index')
  const library = page.locator('.library-pane')
  const detail = page.locator('.detail-pane')
  await expect(navigation).toBeVisible()
  await expect(library).toBeVisible()
  await expect(detail).toBeVisible()
  const [navigationBox, libraryBox, detailBox] = await Promise.all([
    navigation.boundingBox(), library.boundingBox(), detail.boundingBox(),
  ])
  expect(navigationBox?.x).toBeLessThan(libraryBox?.x ?? 0)
  expect(libraryBox?.x).toBeLessThan(detailBox?.x ?? 0)
  await expect(page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).resolves.toBe(0)
  await page.screenshot({ path: testInfo.outputPath('career-material-desktop-1440x900.png'), fullPage: true })
})

test('keeps the desktop material editor wide with a visible action bar', async ({ page }, testInfo) => {
  await mockAuthenticatedApi(page)
  await page.setViewportSize({ width: 1440, height: 900 })

  await page.goto('/career-materials')
  await page.locator('.new-icon-action').click()

  const workspace = page.locator('.material-workspace')
  const editor = page.locator('.material-editor')
  const formScroll = page.locator('.form-scroll')
  const actions = page.locator('.editor-actions')
  await expect(editor).toBeVisible()
  await expect(page.locator('.form-section')).toHaveCount(2)
  await expect(actions).toBeVisible()
  await expect(formScroll).toHaveCSS('overflow-y', 'auto')

  const [workspaceBox, editorBox, formScrollBox, actionsBox] = await Promise.all([
    workspace.boundingBox(), editor.boundingBox(), formScroll.boundingBox(), actions.boundingBox(),
  ])
  expect(editorBox?.width ?? 0).toBeGreaterThanOrEqual(460)
  expect(formScrollBox?.width ?? 0).toBeGreaterThanOrEqual((editorBox?.width ?? 0) * .9)
  expect(actionsBox?.width ?? 0).toBeGreaterThanOrEqual((editorBox?.width ?? 0) * .9)
  expect((actionsBox?.y ?? 0) + (actionsBox?.height ?? 0)).toBeLessThanOrEqual((workspaceBox?.y ?? 0) + (workspaceBox?.height ?? 0) + 1)
  await expect(page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).resolves.toBe(0)
  await page.screenshot({ path: testInfo.outputPath('career-material-editor-desktop-1440x900.png'), fullPage: true })
})

test('opens material details in a tablet drawer', async ({ page }, testInfo) => {
  await mockAuthenticatedApi(page)
  await page.setViewportSize({ width: 1024, height: 768 })
  const summary = { id: 88, materialType: 'PROJECT_EXPERIENCE', title: 'Platform migration', usagePreference: 'PREFERRED', updatedAt: now }
  await page.route('**/api/career-materials/search*', route => route.fulfill({ json: response(materialSearch([summary])) }))
  await page.route('**/api/career-materials/88', route => route.fulfill({ json: response({
    ...summary, contentJson: { outcome: 'Zero-downtime migration.' }, sourceText: 'Moved the platform without downtime.', createdAt: now,
  }) }))

  await page.goto('/career-materials')
  await page.getByRole('article', { name: summary.title }).locator('.row-select').click()
  const drawer = page.locator('.detail-pane')
  await expect(drawer).toHaveCSS('position', 'fixed')
  await expect(drawer).toHaveCSS('transform', 'matrix(1, 0, 0, 1, 0, 0)')
  await expect(page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).resolves.toBe(0)
  await page.screenshot({ path: testInfo.outputPath('career-material-tablet-1024x768.png'), fullPage: true })
})

test('keeps the material-first workspace and full-screen composer usable on mobile', async ({ page }, testInfo) => {
  await mockAuthenticatedApi(page)
  await page.setViewportSize({ width: 390, height: 844 })

  await page.goto('/career-materials')
  await expect(page.locator('.material-workspace')).toBeVisible()
  await expect(page.locator('.profile-index-entry')).toBeVisible()
  await expect(page.locator('.search-control input')).toBeVisible()
  await expect(page.locator('.error-state')).toHaveCount(0)
  await expect(page.locator('.material-form')).toHaveCount(0)
  await page.locator('.new-icon-action').click()
  await expect(page.locator('.material-form')).toBeVisible()
  await expect(page.locator('.detail-pane')).toHaveCSS('position', 'fixed')
  await expect(page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).resolves.toBe(0)
  await page.screenshot({ path: testInfo.outputPath('career-material-mobile-390x844.png'), fullPage: true })
})

test('keeps workspace filters in the URL and only loads details for the selected material', async ({ page }) => {
  await mockAuthenticatedApi(page)
  const summary = { id: 91, materialType: 'SKILL', title: 'Spring platform delivery', usagePreference: 'PREFERRED', updatedAt: now }
  const detail = { ...summary, contentJson: { name: 'Spring Boot' }, sourceText: 'Delivered a reliable Spring platform.', createdAt: now }
  const searchUrls: string[] = []
  let detailRequests = 0
  await page.route('**/api/career-materials/search*', route => {
    searchUrls.push(route.request().url())
    return route.fulfill({ json: response(materialSearch([summary])) })
  })
  await page.route('**/api/career-materials/91', route => {
    detailRequests += 1
    return route.fulfill({ json: response(detail) })
  })

  await page.goto('/career-materials?q=Spring&type=SKILL&usage=PREFERRED&sort=title,asc')
  await expect(page.locator('.search-control input')).toHaveValue('Spring')
  await expect(page.getByRole('article', { name: summary.title })).toBeVisible()
  expect(detailRequests).toBe(0)

  await page.getByRole('article', { name: summary.title }).locator('.row-select').click()
  await expect(page).toHaveURL(/selected=91/)
  await expect(page.locator('.material-detail h2')).toHaveText(summary.title)
  expect(detailRequests).toBe(1)

  await page.locator('.search-control input').fill('platform')
  await expect(page).toHaveURL(/q=platform/)
  await expect(page).not.toHaveURL(/selected=/)
  expect(searchUrls.some(url => new URL(url).searchParams.get('q') === 'platform')).toBe(true)
})

test('paginates material results and keeps URL and API page indexes aligned', async ({ page }) => {
  await mockAuthenticatedApi(page)
  const requestedPages: string[] = []
  await page.route('**/api/career-materials/search*', route => {
    const apiPage = new URL(route.request().url()).searchParams.get('page') ?? '0'
    requestedPages.push(apiPage)
    const item = { id: Number(apiPage) + 1, materialType: 'SKILL', title: `Skill page ${Number(apiPage) + 1}`, usagePreference: 'NORMAL', updatedAt: now }
    return route.fulfill({ json: response(materialSearch([item], { page: Number(apiPage), totalElements: 51, totalPages: 3 })) })
  })
  await page.route('**/api/career-materials/1', route => route.fulfill({ json: response({
    id: 1, materialType: 'SKILL', title: 'Skill page 1', usagePreference: 'NORMAL', updatedAt: now,
    contentJson: {}, sourceText: '', createdAt: now,
  }) }))

  await page.goto('/career-materials?selected=1')
  await expect(page.locator('.pagination')).toBeVisible()
  await page.locator('.pagination button').last().click()
  await expect(page).toHaveURL(/page=2/)
  await expect(page).not.toHaveURL(/selected=/)
  await expect(page.getByRole('article', { name: 'Skill page 2' })).toBeVisible()
  expect(requestedPages).toContain('1')
})

test('retries a failed material workspace search', async ({ page }) => {
  await mockAuthenticatedApi(page)
  let attempts = 0
  await page.route('**/api/career-materials/search*', route => {
    attempts += 1
    if (attempts === 1) return route.fulfill({ status: 503, json: { code: 503, message: 'unavailable' } })
    const item = { id: 2, materialType: 'PROJECT_EXPERIENCE', title: 'Recovered project', usagePreference: 'NORMAL', updatedAt: now }
    return route.fulfill({ json: response(materialSearch([item])) })
  })

  await page.goto('/career-materials')
  await expect(page.locator('.error-state')).toBeVisible()
  await page.locator('.error-state button').click()
  await expect(page.getByRole('article', { name: 'Recovered project' })).toBeVisible()
  await expect(page.locator('.error-state')).toHaveCount(0)
})

test('protects an edited mobile material and restores the list position after closing', async ({ page }) => {
  await mockAuthenticatedApi(page)
  await page.setViewportSize({ width: 390, height: 844 })
  const summaries = Array.from({ length: 12 }, (_, index) => ({
    id: 120 + index,
    materialType: 'HIGHLIGHT',
    title: `Evidence item ${index + 1}`,
    usagePreference: 'NORMAL',
    updatedAt: now,
  }))
  const selected = summaries[9]
  await page.route('**/api/career-materials/search*', route => route.fulfill({ json: response(materialSearch(summaries)) }))
  await page.route(`**/api/career-materials/${selected.id}`, route => route.fulfill({ json: response({
    ...selected,
    contentJson: { summary: 'Verifiable evidence.' },
    sourceText: 'Verifiable evidence.',
    createdAt: now,
  }) }))

  await page.goto('/career-materials')
  const row = page.getByRole('article', { name: selected.title })
  await row.scrollIntoViewIfNeeded()
  const listScrollY = await page.evaluate(() => window.scrollY)
  await row.locator('.row-select').click()
  await page.locator('.detail-actions .btn-primary').click()
  await page.locator('.material-form').getByLabel('标题').fill('Edited evidence title')

  page.once('dialog', dialog => dialog.dismiss())
  await page.locator('.close-action').click()
  await expect(page.locator('.material-form')).toBeVisible()

  page.once('dialog', dialog => dialog.accept())
  await page.locator('.close-action').click()
  await expect(page.locator('.material-form')).toHaveCount(0)
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(listScrollY)
})

test('keeps an edited material when the user cancels a delete', async ({ page }) => {
  await mockAuthenticatedApi(page)
  const summaries = [
    { id: 301, materialType: 'HIGHLIGHT', title: 'Editing target', usagePreference: 'NORMAL', updatedAt: now },
    { id: 302, materialType: 'HIGHLIGHT', title: 'Delete target', usagePreference: 'NORMAL', updatedAt: now },
  ]
  let deleteRequests = 0
  await page.route('**/api/career-materials/search*', route => route.fulfill({ json: response(materialSearch(summaries)) }))
  await page.route('**/api/career-materials/301', route => route.fulfill({ json: response({
    ...summaries[0], contentJson: {}, sourceText: 'Evidence', createdAt: now,
  }) }))
  await page.route('**/api/career-materials/302', route => {
    if (route.request().method() === 'DELETE') deleteRequests += 1
    return route.fulfill({ json: response(null) })
  })

  await page.goto('/career-materials')
  await page.getByRole('article', { name: 'Editing target' }).getByRole('button', { name: '编辑' }).click()
  const title = page.locator('.material-form input').first()
  await title.fill('Unsaved title')
  let dialogCount = 0
  const dialogMessages: string[] = []
  page.on('dialog', async dialog => {
    dialogCount += 1
    dialogMessages.push(dialog.message())
    if (dialogCount === 1) await dialog.accept()
    else await dialog.dismiss()
  })
  await page.getByRole('article', { name: 'Delete target' }).locator('.row-actions .danger').click()
  await expect(title).toHaveValue('Unsaved title')
  expect(dialogCount).toBe(2)
  expect(dialogMessages[1]).toBe('删除资料不会改写已创建版本的历史快照，确定继续吗？')
  expect(dialogMessages[1]).not.toContain('�')
  expect(deleteRequests).toBe(0)
})

test('loads complete material details before editing a highlight', async ({ page }) => {
  await mockAuthenticatedApi(page)
  const summary = {
    id: 77,
    materialType: 'HIGHLIGHT',
    title: 'Cross-team technical leadership',
    usagePreference: 'PREFERRED',
    updatedAt: now,
  }
  const detail = {
    ...summary,
    contentJson: { summary: 'Led a six-person platform reliability initiative.' },
    sourceText: 'Led six engineers and reduced production incidents by 45%.',
    createdAt: now,
  }
  let updatePayload: unknown

  await page.route('**/api/career-materials/search*', route => route.fulfill({ json: response(materialSearch([summary])) }))
  await page.route('**/api/career-materials', route => route.fulfill({ json: response([summary]) }))
  await page.route('**/api/career-materials/77', async route => {
    if (route.request().method() === 'PATCH') {
      updatePayload = route.request().postDataJSON()
      return route.fulfill({ json: response(detail) })
    }
    return route.fulfill({ json: response(detail) })
  })

  await page.goto('/career-materials')
  const card = page.getByRole('article', { name: summary.title })
  await card.getByRole('button', { name: '编辑' }).click()

  const form = page.locator('.material-form')
  await expect(form.getByLabel('资料类型')).toHaveValue('HIGHLIGHT')
  await expect(form.getByLabel('来源原文')).toHaveValue(detail.sourceText)
  await expect(form.getByLabel('资料 JSON')).toHaveValue(/platform reliability initiative/)

  await form.getByLabel('来源原文').fill('')
  await form.getByRole('button', { name: '保存修改' }).click()
  await expect.poll(() => updatePayload).toMatchObject({
    materialType: 'HIGHLIGHT',
    sourceText: '',
    contentJson: detail.contentJson,
  })
})

test('ignores a stale detail response after another material is selected', async ({ page }) => {
  await mockAuthenticatedApi(page)
  const summaries = [
    { id: 201, materialType: 'HIGHLIGHT', title: 'Slow detail', usagePreference: 'NORMAL', updatedAt: now },
    { id: 202, materialType: 'HIGHLIGHT', title: 'Current detail', usagePreference: 'NORMAL', updatedAt: now },
  ]
  let releaseSlowDetail!: () => void
  const slowDetailGate = new Promise<void>(resolve => { releaseSlowDetail = resolve })
  await page.route('**/api/career-materials/search*', route => route.fulfill({ json: response(materialSearch(summaries)) }))
  await page.route('**/api/career-materials/201', async route => {
    await slowDetailGate
    return route.fulfill({ json: response({ ...summaries[0], contentJson: {}, sourceText: 'Slow', createdAt: now }) })
  })
  await page.route('**/api/career-materials/202', route => route.fulfill({ json: response({
    ...summaries[1], contentJson: {}, sourceText: 'Current', createdAt: now,
  }) }))

  await page.goto('/career-materials')
  await page.getByRole('article', { name: 'Slow detail' }).getByRole('button', { name: '编辑' }).click()
  await page.getByRole('article', { name: 'Current detail' }).locator('.row-select').click()
  await expect(page.locator('.material-detail h2')).toHaveText('Current detail')
  releaseSlowDetail()
  await expect(page.locator('.material-form')).toHaveCount(0)
  await expect(page.locator('.material-detail h2')).toHaveText('Current detail')
})

test('preserves a legacy skill proficiency value when editing', async ({ page }) => {
  await mockAuthenticatedApi(page)
  const summary = {
    id: 78,
    materialType: 'SKILL_EVIDENCE',
    title: 'Spring Boot delivery',
    usagePreference: 'NORMAL',
    updatedAt: now,
  }
  const detail = {
    ...summary,
    contentJson: {
      skillName: 'Spring Boot', category: '后端框架', proficiency: '熟练', yearsOfExperience: '3 年',
      lastUsedAt: '2026 年', relatedMaterialIds: [], applicationDescription: '用于交易服务', outcomeEvidence: '稳定支撑高峰流量',
    },
    sourceText: '',
    createdAt: now,
  }
  let updatePayload: any

  await page.route('**/api/career-materials/search*', route => route.fulfill({ json: response(materialSearch([summary])) }))
  await page.route('**/api/career-materials', route => route.fulfill({ json: response([summary]) }))
  await page.route('**/api/career-materials/78', async route => {
    if (route.request().method() === 'PATCH') updatePayload = route.request().postDataJSON()
    return route.fulfill({ json: response(detail) })
  })

  await page.goto('/career-materials')
  await page.getByRole('article', { name: summary.title }).getByRole('button', { name: '编辑' }).click()

  const form = page.locator('.material-form')
  await expect(form.getByLabel('熟练度')).toHaveValue('熟练')
  await form.getByRole('button', { name: '保存修改' }).click()
  await expect.poll(() => updatePayload).toMatchObject({
    materialType: 'SKILL_EVIDENCE',
    contentJson: { proficiency: '熟练' },
  })
})

test('loads a personal profile suggestion without saving it automatically', async ({ page }) => {
  await mockAuthenticatedApi(page)
  let savedProfile: unknown
  await page.route('**/api/personal-profile/import-suggestion*', route => route.fulfill({ json: response({
    fullName: 'BYNeko', email: 'e2e-profile@example.com', phone: '13800000000', location: '杭州',
    website: 'https://example.com', profileSummary: '专注高并发后端系统。',
  }) }))
  await page.route('**/api/personal-profile', async route => {
    if (route.request().method() === 'PUT') {
      savedProfile = route.request().postDataJSON()
      return route.fulfill({ json: response(savedProfile) })
    }
    return route.fulfill({ json: response({ fullName: '', email: '', phone: '', location: '', website: '', profileSummary: '' }) })
  })

  await page.goto('/career-materials')
  await page.locator('.profile-index-entry').click()
  await page.getByLabel('从已有简历提取建议').selectOption('1')
  await page.getByRole('button', { name: '读取建议' }).click()
  await expect(page.getByLabel('姓名')).toHaveValue('BYNeko')
  expect(savedProfile).toBeUndefined()
  await page.getByRole('button', { name: '保存个人档案' }).click()
  await expect.poll(() => savedProfile).toMatchObject({ fullName: 'BYNeko', email: 'e2e-profile@example.com' })
})

test('restores the server profile after the user discards edits', async ({ page }) => {
  await mockAuthenticatedApi(page)
  await page.route('**/api/personal-profile', route => route.fulfill({ json: response({
    fullName: 'Persisted Name', email: '', phone: '', location: '', website: '', profileSummary: '',
    targetRoleTitles: [], targetSeniority: '', targetIndustries: [], targetWorkPreferences: [], careerPositioningSummary: '',
  }) }))

  await page.goto('/career-materials')
  await page.locator('.profile-index-entry').click()
  const fullName = page.locator('.profile-fields input').first()
  await expect(fullName).toHaveValue('Persisted Name')
  await fullName.fill('Unsaved Draft')
  page.once('dialog', dialog => dialog.accept())
  await page.locator('.profile-workspace .back-action').click()
  await page.locator('.profile-index-entry').click()
  await expect(page.locator('.profile-fields input').first()).toHaveValue('Persisted Name')
})

test('creates an achievement material through the structured form without exposing JSON', async ({ page }) => {
  await mockAuthenticatedApi(page)
  const workMaterial = { id: 41, materialType: 'WORK_EXPERIENCE', title: '交易平台后端开发', usagePreference: 'NORMAL', updatedAt: now }
  let createPayload: unknown
  await page.route('**/api/career-materials*', route => {
    const url = new URL(route.request().url())
    if (route.request().method() === 'POST') {
      createPayload = route.request().postDataJSON()
      return route.fulfill({ json: response({ id: 80, ...createPayload, updatedAt: now, createdAt: now }) })
    }
    if (url.pathname.endsWith('/41')) return route.fulfill({ json: response({ ...workMaterial, contentJson: {}, sourceText: '负责交易服务', createdAt: now }) })
    if (url.pathname.endsWith('/search')) return route.fulfill({ json: response(materialSearch([workMaterial])) })
    return route.fulfill({ json: response([workMaterial]) })
  })

  await page.goto('/career-materials')
  await page.locator('.new-icon-action').click()
  const form = page.locator('.material-form')
  await form.getByLabel('资料类型').selectOption('ACHIEVEMENT')
  await expect(form.getByLabel('关联工作或项目')).toBeVisible()
  await expect(form.getByLabel('资料 JSON')).toHaveCount(0)
  await form.getByLabel('标题').fill('交易链路性能优化')
  await form.getByLabel('关联工作或项目').selectOption('41')
  await form.getByLabel('时间范围').fill('2025 年 Q2')
  await form.getByLabel('业务场景').fill('高峰期交易接口出现延迟')
  await form.getByLabel('采取行动').fill('重构缓存和异步处理链路')
  await form.getByLabel('成果说明').fill('核心接口稳定性显著提升')
  await form.getByLabel('指标名称').fill('P99 延迟')
  await form.getByLabel('展示口径').selectOption('RANGE')
  await form.getByLabel('成果展示值').fill('延迟降低约三成')
  await form.getByRole('button', { name: '保存资料' }).click()

  await expect.poll(() => createPayload).toMatchObject({
    materialType: 'ACHIEVEMENT', title: '交易链路性能优化',
    contentJson: {
      relatedMaterialId: 41, scenario: '高峰期交易接口出现延迟', action: '重构缓存和异步处理链路',
      period: '2025 年 Q2', metricName: 'P99 延迟',
      metricDisplayMode: 'RANGE', metricDisplayValue: '延迟降低约三成',
    },
  })
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
  await page.route('**/api/resumes/1/versions**', async route => {
    if (route.request().method() === 'GET') return route.fulfill({ json: response([version]) })
    saved = route.request().postDataJSON()
    return route.fulfill({ json: response(editorVersion) })
  })

  await page.goto('/resumes/1/edit')
  await expect(page.locator('.studio-grid')).toBeVisible()
  await page.locator('.studio-actions').getByRole('button', { name: '选择模板' }).click()
  await page.getByRole('dialog', { name: '选择简历模板' }).getByRole('button', { name: /现代/ }).click()
  await page.getByRole('dialog', { name: '选择简历模板' }).getByRole('button', { name: '关闭' }).click()
  await page.locator('.studio-actions').getByRole('button', { name: '设计与高级' }).click()
  await expect(page.locator('.editor-sidebar')).toBeVisible()
  await expect(page.locator('.design-live-preview .resume-paper')).toBeVisible()
  await expect(page.locator('.design-workspace-layout > .editor-command-center')).toHaveCSS('position', 'sticky')
  await expect(page.locator('.design-live-preview-stage')).toHaveCSS('overflow-y', 'visible')
  await expect(page.locator('#resume-form')).toHaveCount(0)
  const bodySize = page.getByRole('slider', { name: '正文字号' })
  await expect(bodySize).toHaveValue('13')
  await bodySize.fill('16')
  const headingSize = page.getByRole('slider', { name: '标题字号' })
  await headingSize.fill('18')
  const pagePadding = page.getByRole('slider', { name: '页面留白' })
  await pagePadding.fill('72')
  await expect(page.locator('.design-live-preview .resume-paper')).toHaveCSS('padding-top', '72px')
  await page.getByLabel('字体风格').selectOption('songti')
  await expect(page.locator('.design-live-preview .resume-paper')).toHaveCSS('--resume-body-size', '16px')
  await page.getByRole('button', { name: '预览', exact: true }).click()
  await expect(page.locator('.resume-paper')).toHaveCSS('--resume-body-size', '16px')
  await expect(page.locator('.paper-header h2')).toHaveCSS('font-size', '41.5385px')
  await expect(page.locator('.resume-paper')).toHaveCSS('--resume-font-family', '"Songti SC", SimSun, serif')
  await page.locator('.resume-preview-workspace').getByRole('button', { name: '保存新版本' }).click()
  await expect.poll(() => saved).toMatchObject({ resumeJson: { layout: { bodyFontSize: 16, headingFontSize: 18, fontFamily: 'songti' } } })
})

test('applies an AI-optimized personal summary from the section assistant', async ({ page }) => {
  await mockAuthenticatedApi(page)
  const editorVersion = {
    ...version,
    resumeJson: {
      basics: { name: 'BYNeko', title: '后端工程师', summary: '负责后端平台开发。' },
      work: [], education: [], skills: [], projects: [], certificates: [], languages: [],
    },
  }
  await page.route('**/api/resume-versions/11', route => route.fulfill({ json: response(editorVersion) }))
  await page.route('**/api/ai/inline-optimize', route => route.fulfill({ json: response({ id: 71, status: 'PENDING' }) }))
  await page.route('**/api/ai/tasks/71', route => route.fulfill({ json: response({
    id: 71,
    status: 'SUCCESS',
    resultJson: {
      originalContent: '负责后端平台开发。',
      candidates: [{ content: '主导高并发后端平台的设计与交付。', suggestion: '强化行动表达' }],
      requiresManualConfirmation: true,
    },
  }) }))

  await page.goto('/resumes/1/edit')
  await page.locator('#resume-basics').getByRole('button', { name: 'AI 优化' }).click()
  const assistant = page.locator('.ai-assistant-panel')
  await expect(assistant.getByText('主导高并发后端平台的设计与交付。')).toBeVisible()
  const applyButton = assistant.getByRole('button', { name: '采纳并写回' })
  await expect(applyButton).toBeVisible()
  await applyButton.click()

  await expect(page.locator('#resume-basics textarea')).toHaveValue('主导高并发后端平台的设计与交付。')
  await expect(assistant).toBeHidden()
})

test('keeps return and preview actions inside the design workspace', async ({ page }) => {
  await mockAuthenticatedApi(page)
  const editorVersion = { ...version, resumeJson: { basics: { name: 'Alice' }, work: [], education: [], skills: [], projects: [], certificates: [], awards: [], languages: [] } }
  await page.route('**/api/resume-versions/11', route => route.fulfill({ json: response(editorVersion) }))

  await page.goto('/resumes/1/edit')
  await page.evaluate(() => window.scrollTo(0, 320))
  await page.locator('.resume-editor-navigation').getByRole('button', { name: '设计与高级' }).click()
  const actions = page.locator('.design-workspace-actions')
  await expect(actions.getByRole('button', { name: '返回内容' })).toBeVisible()
  await expect(actions.getByRole('button', { name: '预览简历' })).toBeVisible()
  await actions.getByRole('button', { name: '预览简历' }).click()
  await expect(page.locator('.resume-preview-workspace')).toBeVisible()
  await page.getByRole('button', { name: '返回编辑' }).click()
  await actions.getByRole('button', { name: '返回内容' }).click()
  await expect(page.locator('#resume-form')).toBeVisible()
  await expect(page.locator('#resume-basics')).toBeVisible()
})

test('opens template selection as an independent workspace entry', async ({ page }) => {
  await mockAuthenticatedApi(page)
  const editorVersion = { ...version, resumeJson: { basics: { name: 'Alice' }, work: [], education: [], skills: [], projects: [], certificates: [], awards: [], languages: [], template: { code: 'classic' } } }
  await page.route('**/api/resume-versions/11', route => route.fulfill({ json: response(editorVersion) }))
  await page.goto('/resumes/1/edit')
  await page.locator('.studio-actions').getByRole('button', { name: '选择模板' }).click()
  await expect(page.getByRole('dialog', { name: '选择简历模板' })).toBeVisible()
  await page.getByRole('dialog', { name: '选择简历模板' }).getByRole('button', { name: /现代/ }).click()
  await expect(page.getByRole('dialog', { name: '选择简历模板' })).toContainText('当前使用')
  await page.getByRole('dialog', { name: '选择简历模板' }).getByRole('button', { name: '关闭' }).click()
  await expect(page.getByRole('dialog', { name: '选择简历模板' })).toHaveCount(0)
})

test('shows extended resume sections and renders their structured content in preview', async ({ page }) => {
  await mockAuthenticatedApi(page)
  const editorVersion = { ...version, resumeJson: allSectionsResume }
  let saved: any
  await page.route('**/api/resume-versions/11', route => route.fulfill({ json: response(editorVersion) }))
  await page.route('**/api/resumes/1/versions**', async route => {
    if (route.request().method() === 'GET') return route.fulfill({ json: response([version]) })
    saved = route.request().postDataJSON()
    return route.fulfill({ json: response({ ...editorVersion, id: 12, versionNo: 2, resumeJson: saved.resumeJson }) })
  })
  await page.goto('/resumes/1/edit')
  const navigation = page.locator('.resume-editor-navigation')
  for (const name of ['职业目标', '个人链接', '实习 / 志愿经历', '培训课程', '研究成果', '自定义模块']) {
    await expect(navigation.getByRole('button', { name: new RegExp(name) })).toBeVisible()
  }
  await page.getByRole('button', { name: '预览简历' }).click()
  const preview = page.locator('.resume-preview-workspace .resume-paper')
  for (const marker of ['SUMMARY_MARKER', 'OBJECTIVE_MARKER', 'LINK_MARKER', 'WORK_MARKER', 'VOLUNTEERING_MARKER', 'SKILL_MARKER', 'PROJECT_MARKER', 'EDUCATION_MARKER', 'COURSE_MARKER', 'CERTIFICATE_MARKER', 'PUBLICATION_MARKER', 'AWARD_MARKER', 'LANGUAGE_MARKER', 'CUSTOM_ONE_MARKER', 'CUSTOM_TWO_MARKER']) {
    await expect(preview).toContainText(marker)
  }
  await expect(preview).toContainText('Shanghai')
  await expect(preview).not.toContainText('[object Object]')
  await expect(preview).not.toContainText('IGNORED_UNKNOWN_MARKER')
  const visualOrder = await preview.locator(':scope > section').evaluateAll(sections => sections
    .map(section => ({ order: Number(getComputedStyle(section).order), text: section.textContent ?? '' }))
    .sort((left, right) => left.order - right.order)
    .map(section => section.text))
  expect(visualOrder.findIndex(text => text.includes('AWARD_MARKER'))).toBeLessThan(visualOrder.findIndex(text => text.includes('OBJECTIVE_MARKER')))
  expect(visualOrder.findIndex(text => text.includes('OBJECTIVE_MARKER'))).toBeLessThan(visualOrder.findIndex(text => text.includes('WORK_MARKER')))
  await page.getByRole('button', { name: '返回编辑' }).click()
  await navigation.getByRole('button', { name: '设计与高级' }).click()
  await expect(page.locator('.design-live-preview .resume-paper')).toContainText('CUSTOM_TWO_MARKER')
  await page.getByRole('slider').first().fill('14')
  await page.getByRole('button', { name: '保存新版本' }).click()
  await expect.poll(() => saved).toMatchObject({ resumeJson: {
    basics: allSectionsResume.basics,
    customSections: allSectionsResume.customSections,
    layout: { ...allSectionsResume.layout, bodyFontSize: 14 },
    unknownFutureField: allSectionsResume.unknownFutureField,
  } })
})

test('blocks editing after a resume load failure and recovers through retry', async ({ page }) => {
  await mockAuthenticatedApi(page)
  let failLoad = true
  await page.route('**/api/resumes/1', route => {
    if (failLoad) return route.fulfill({ status: 500, json: { code: 500, message: 'service unavailable', data: null } })
    return route.fulfill({ json: response(resume) })
  })
  await page.route('**/api/resume-versions/11', route => route.fulfill({ json: response({ ...version, resumeJson: { basics: { name: 'Alice' }, work: [], education: [], skills: [], projects: [], certificates: [], languages: [] } }) }))

  await page.goto('/resumes/1/edit')
  await expect(page.getByRole('heading', { name: '无法加载简历' })).toBeVisible()
  await expect(page.locator('#resume-form')).toHaveCount(0)
  await expect(page.locator('.resume-editor-navigation')).toHaveCount(0)
  await expect(page.getByRole('link', { name: '返回简历列表' })).toHaveAttribute('href', '/resumes')

  failLoad = false
  await page.getByRole('button', { name: '重试加载' }).click()
  await expect(page.locator('.resume-editor-navigation')).toBeVisible()
  await expect(page.getByLabel('姓名')).toHaveValue('Alice')
})

test('keeps an empty editor available when a resume has no current version', async ({ page }) => {
  await mockAuthenticatedApi(page)
  const emptyResume = { ...resume, currentVersionId: null }
  await page.route('**/api/resumes/1', route => route.fulfill({ json: response(emptyResume) }))
  await page.route('**/api/resumes/1/versions**', route => route.fulfill({ json: response([]) }))

  await page.goto('/resumes/1/edit')
  await expect(page.getByRole('heading', { name: '无法加载简历' })).toHaveCount(0)
  await expect(page.locator('#resume-form')).toBeVisible()
  await expect(page.getByLabel('姓名')).toHaveValue('')
})

test('saves content and design changes through the manual version endpoint', async ({ page }) => {
  await mockAuthenticatedApi(page)
  const editorVersion = {
    ...version,
    resumeJson: {
      basics: { name: 'Alice', title: 'Engineer', summary: '' }, work: [], education: [], skills: [], projects: [], certificates: [], awards: [], languages: [],
      template: { code: 'classic' },
    },
  }
  let saved: any
  await page.route('**/api/resume-versions/11', route => route.fulfill({ json: response(editorVersion) }))
  await page.route('**/api/resumes/1/versions**', async route => {
    if (route.request().method() === 'GET') return route.fulfill({ json: response([version]) })
    saved = route.request().postDataJSON()
    return route.fulfill({ json: response({ ...version, id: 12, versionNo: 2, resumeJson: saved.resumeJson }) })
  })

  await page.goto('/resumes/1/edit')
  await page.getByLabel('姓名').fill('Alice Chen')
  await page.locator('.resume-editor-navigation').getByRole('button', { name: /工作经历/ }).click()
  await page.getByRole('button', { name: '添加工作经历' }).click()
  await page.getByLabel('公司').fill('ACME')
  await page.getByLabel('职位').fill('Platform Engineer')
  await page.locator('.resume-editor-navigation').getByRole('button', { name: /技能/ }).click()
  await page.locator('#resume-skills').getByRole('button', { name: '添加技能' }).click()
  await page.getByPlaceholder('例如：Spring Boot').fill('Kubernetes')
  await page.locator('.studio-actions').getByRole('button', { name: '选择模板' }).click()
  await page.getByRole('dialog', { name: '选择简历模板' }).getByRole('button', { name: /现代/ }).click()
  await page.getByRole('dialog', { name: '选择简历模板' }).getByRole('button', { name: '关闭' }).click()
  await page.locator('.resume-editor-navigation').getByRole('button', { name: /奖项荣誉/ }).click()
  await page.locator('#resume-awards').getByRole('button', { name: '添加奖项' }).click()
  await page.getByLabel('奖项名称').fill('Outstanding Engineer')
  await expect(page.locator('#resume-awards')).toContainText('奖项 1')
  await page.locator('.studio-actions').getByRole('button', { name: '设计与高级' }).click()
  await page.getByRole('button', { name: '预览', exact: true }).click()
  await expect(page.locator('.resume-preview-workspace')).toBeVisible()
  await page.getByRole('button', { name: '返回编辑' }).click()
  await page.locator('.studio-actions').getByRole('button', { name: '保存新版本' }).click()

  await expect.poll(() => saved).toMatchObject({
    resumeJson: {
      basics: { name: 'Alice Chen' },
      work: [{ company: 'ACME', position: 'Platform Engineer' }],
      skills: [{ name: 'Kubernetes' }],
      awards: [{ name: 'Outstanding Engineer' }],
      template: { code: 'modern' },
    },
  })
  await expect(page).toHaveURL(/\/resumes\/1$/)
  await expect.poll(() => page.evaluate(() => localStorage.getItem('intelligent-resume.editor-draft.99.1'))).toBeNull()
})

test('adds a saved award material to the matching resume section', async ({ page }) => {
  await mockAuthenticatedApi(page)
  const editorVersion = {
    ...version,
    resumeJson: { basics: { name: 'Alice' }, work: [], education: [], skills: [], projects: [], certificates: [], awards: [], languages: [], template: { code: 'classic' } },
  }
  const award = { id: 88, materialType: 'AWARD', title: 'Engineering Excellence', usagePreference: 'PREFERRED', updatedAt: now }
  let saved: any
  await page.route('**/api/resume-versions/11', route => route.fulfill({ json: response(editorVersion) }))
  await page.route('**/api/career-materials', route => route.fulfill({ json: response([award]) }))
  await page.route('**/api/career-materials/88', route => route.fulfill({ json: response({ ...award, contentJson: { issuer: 'ACME', date: '2025-06', description: 'Recognized for a reliable platform launch.' }, sourceText: null, createdAt: now }) }))
  await page.route('**/api/resumes/1/versions**', async route => {
    if (route.request().method() === 'GET') return route.fulfill({ json: response([version]) })
    saved = route.request().postDataJSON()
    return route.fulfill({ json: response({ ...version, id: 12, versionNo: 2, resumeJson: saved.resumeJson }) })
  })

  await page.goto('/resumes/1/edit')
  await page.locator('.resume-editor-navigation').getByRole('button', { name: /奖项荣誉/ }).click()
  await page.getByRole('button', { name: '从资料库添加' }).click()
  await page.getByLabel('选择资料').selectOption('88')
  await page.getByRole('button', { name: '写入当前章节' }).click()
  await expect(page.getByLabel('奖项名称')).toHaveValue('Engineering Excellence')
  await expect(page.getByLabel('授予机构')).toHaveValue('ACME')
  await page.locator('.editor-save-dock').getByRole('button', { name: '保存新版本' }).click()
  await expect.poll(() => saved).toMatchObject({ resumeJson: { awards: [{ name: 'Engineering Excellence', issuer: 'ACME', date: '2025-06' }] } })
})

test('reloads editor state when the route changes to another resume id', async ({ page }) => {
  await mockAuthenticatedApi(page)
  const secondResume = { ...resume, id: 2, title: 'Second resume', currentVersionId: 22 }
  const firstVersion = { ...version, resumeJson: { basics: { name: 'Alice' }, work: [], education: [], skills: [], projects: [], certificates: [], languages: [] } }
  const secondVersion = { ...version, id: 22, resumeId: 2, resumeJson: { basics: { name: 'Bob' }, work: [], education: [], skills: [], projects: [], certificates: [], languages: [] } }
  await page.route('**/api/resumes/2', route => route.fulfill({ json: response(secondResume) }))
  await page.route('**/api/resume-versions/11', route => route.fulfill({ json: response(firstVersion) }))
  await page.route('**/api/resume-versions/22', route => route.fulfill({ json: response(secondVersion) }))

  await page.goto('/resumes/1/edit')
  await expect(page.getByLabel('姓名')).toHaveValue('Alice')
  await page.evaluate(() => {
    window.history.pushState({}, '', '/resumes/2/edit')
    window.dispatchEvent(new PopStateEvent('popstate'))
  })

  await expect(page).toHaveURL(/\/resumes\/2\/edit$/)
  await expect(page.getByLabel('姓名')).toHaveValue('Bob')
})

test('shows a retryable error when the editor cannot load the material library', async ({ page }) => {
  await mockAuthenticatedApi(page)
  await page.route('**/api/resume-versions/11', route => route.fulfill({ json: response(version) }))
  await page.route('**/api/career-materials', route => route.fulfill({ status: 503, json: response(null) }))

  await page.goto('/resumes/1/edit')
  await page.locator('.resume-editor-navigation').getByRole('button', { name: /奖项荣誉/ }).click()
  await page.getByRole('button', { name: '从资料库添加' }).click()

  await expect(page.locator('.form-error')).toContainText('无法读取资料库，请重试。')
})

test('does not let a pending save clear or redirect a newly opened resume', async ({ page }) => {
  await mockAuthenticatedApi(page)
  const secondResume = { ...resume, id: 2, title: 'Second resume', currentVersionId: 22 }
  const firstVersion = { ...version, resumeJson: { basics: { name: 'Alice' }, work: [], education: [], skills: [], projects: [], certificates: [], languages: [] } }
  const secondVersion = { ...version, id: 22, resumeId: 2, resumeJson: { basics: { name: 'Bob' }, work: [], education: [], skills: [], projects: [], certificates: [], languages: [] } }
  let releaseSave!: () => void
  const saveResponse = new Promise<void>((resolve) => { releaseSave = resolve })
  let saveStarted = false

  await page.addInitScript(() => {
    localStorage.setItem('intelligent-resume.editor-draft.99.1', JSON.stringify({ baseVersionId: 11, content: '{}', summary: '', activeSection: 'basics', updatedAt: new Date().toISOString() }))
    localStorage.setItem('intelligent-resume.editor-draft.99.2', JSON.stringify({ baseVersionId: 22, content: '{}', summary: '', activeSection: 'basics', updatedAt: new Date().toISOString() }))
  })
  await page.route('**/api/resumes/2', route => route.fulfill({ json: response(secondResume) }))
  await page.route('**/api/resume-versions/11', route => route.fulfill({ json: response(firstVersion) }))
  await page.route('**/api/resume-versions/22', route => route.fulfill({ json: response(secondVersion) }))
  await page.route('**/api/resumes/2/versions**', route => route.fulfill({ json: response([secondVersion]) }))
  await page.route('**/api/resumes/1/versions**', async route => {
    if (route.request().method() === 'GET') return route.fulfill({ json: response([firstVersion]) })
    saveStarted = true
    await saveResponse
    return route.fulfill({ json: response({ ...firstVersion, id: 12, versionNo: 2 }) })
  })

  await page.goto('/resumes/1/edit')
  await page.getByLabel('姓名').fill('Alice saved')
  await page.locator('.editor-save-dock').getByRole('button', { name: '保存新版本' }).click()
  await expect.poll(() => saveStarted).toBe(true)
  page.once('dialog', dialog => dialog.accept())
  await page.evaluate(() => {
    window.history.pushState({}, '', '/resumes/2/edit')
    window.dispatchEvent(new PopStateEvent('popstate'))
  })
  await expect(page).toHaveURL(/\/resumes\/2\/edit$/)
  releaseSave()

  await expect.poll(() => page.evaluate(() => localStorage.getItem('intelligent-resume.editor-draft.99.1'))).toBeNull()
  await expect.poll(() => page.evaluate(() => localStorage.getItem('intelligent-resume.editor-draft.99.2'))).not.toBeNull()
  await expect(page.getByLabel('姓名')).toHaveValue('Bob')
})

test('does not show a rejected save from a previous resume on the current route', async ({ page }) => {
  await mockAuthenticatedApi(page)
  const secondResume = { ...resume, id: 2, title: 'Second resume', currentVersionId: 22 }
  const firstVersion = { ...version, resumeJson: { basics: { name: 'Alice' }, work: [], education: [], skills: [], projects: [], certificates: [], languages: [] } }
  const secondVersion = { ...version, id: 22, resumeId: 2, resumeJson: { basics: { name: 'Bob' }, work: [], education: [], skills: [], projects: [], certificates: [], languages: [] } }
  let releaseSave!: () => void
  const saveResponse = new Promise<void>((resolve) => { releaseSave = resolve })
  let saveStarted = false

  await page.route('**/api/resumes/2', route => route.fulfill({ json: response(secondResume) }))
  await page.route('**/api/resume-versions/11', route => route.fulfill({ json: response(firstVersion) }))
  await page.route('**/api/resume-versions/22', route => route.fulfill({ json: response(secondVersion) }))
  await page.route('**/api/resumes/2/versions**', route => route.fulfill({ json: response([secondVersion]) }))
  await page.route('**/api/resumes/1/versions**', async route => {
    if (route.request().method() === 'GET') return route.fulfill({ json: response([firstVersion]) })
    saveStarted = true
    await saveResponse
    return route.fulfill({ status: 500, json: { code: 50000, message: 'Old resume save failed' } })
  })

  await page.goto('/resumes/1/edit')
  await page.getByLabel('姓名').fill('Alice rejected')
  await page.locator('.editor-save-dock').getByRole('button', { name: '保存新版本' }).click()
  await expect.poll(() => saveStarted).toBe(true)
  page.once('dialog', dialog => dialog.accept())
  await page.evaluate(() => {
    window.history.pushState({}, '', '/resumes/2/edit')
    window.dispatchEvent(new PopStateEvent('popstate'))
  })
  await expect(page).toHaveURL(/\/resumes\/2\/edit$/)
  releaseSave()

  await expect(page.getByLabel('姓名')).toHaveValue('Bob')
  await expect(page.locator('.form-error')).toHaveCount(0)
  await expect(page.locator('.editor-save-dock')).toContainText('内容已同步')
})

test('marks loaded sample content as unsaved and enables saving', async ({ page }) => {
  await mockAuthenticatedApi(page)
  const editorVersion = { ...version, resumeJson: { basics: { name: '' }, work: [], education: [], skills: [], projects: [], certificates: [], languages: [] } }
  await page.route('**/api/resume-versions/11', route => route.fulfill({ json: response(editorVersion) }))

  await page.goto('/resumes/1/edit')
  await page.getByRole('button', { name: '加载示例' }).click()

  await expect(page.locator('.editor-save-dock').getByRole('button', { name: '保存新版本' })).toBeEnabled()
})

test('inserts a material into the section that initiated the request', async ({ page }) => {
  await mockAuthenticatedApi(page)
  const editorVersion = {
    ...version,
    resumeJson: { basics: { name: 'Alice' }, work: [], education: [], skills: [], projects: [], certificates: [], awards: [], languages: [], template: { code: 'classic' } },
  }
  const award = { id: 88, materialType: 'AWARD', title: 'Engineering Excellence', usagePreference: 'PREFERRED', updatedAt: now }
  await page.route('**/api/resume-versions/11', route => route.fulfill({ json: response(editorVersion) }))
  await page.route('**/api/career-materials', route => route.fulfill({ json: response([award]) }))
  let releaseMaterial!: () => void
  let markRequestStarted!: () => void
  const materialGate = new Promise<void>(resolve => { releaseMaterial = resolve })
  const requestStarted = new Promise<void>(resolve => { markRequestStarted = resolve })
  await page.route('**/api/career-materials/88', async route => {
    markRequestStarted()
    await materialGate
    await route.fulfill({ json: response({ ...award, contentJson: { issuer: 'ACME' }, sourceText: null, createdAt: now }) })
  })

  await page.goto('/resumes/1/edit')
  await page.locator('.resume-editor-navigation').getByRole('button', { name: /奖项荣誉/ }).click()
  await page.getByRole('button', { name: '从资料库添加' }).click()
  await page.getByLabel('选择资料').selectOption('88')
  await page.getByRole('button', { name: '写入当前章节' }).click()
  await requestStarted
  await page.locator('.resume-editor-navigation').getByRole('button', { name: /工作经历/ }).click()
  releaseMaterial()

  await expect(page.locator('#resume-work')).not.toContainText('Engineering Excellence')
  await page.locator('.resume-editor-navigation').getByRole('button', { name: /奖项荣誉/ }).click()
  await expect(page.getByLabel('奖项名称')).toHaveValue('Engineering Excellence')
})

test('replaces the editor with a full-page resume preview and restores the editing context', async ({ page }) => {
  await mockAuthenticatedApi(page)
  const editorVersion = {
    ...version,
    resumeJson: {
      basics: { name: 'Alice', title: 'Engineer', summary: 'Builds reliable systems.' },
      work: [{ company: 'ACME', position: 'Engineer' }], education: [], skills: [], projects: [], certificates: [], languages: [],
      template: { code: 'classic' },
    },
  }
  await page.route('**/api/resume-versions/11', route => route.fulfill({ json: response(editorVersion) }))

  await page.goto('/resumes/1/edit')
  await expect(page.locator('.resume-editor-navigation')).toBeVisible()
  await expect(page.locator('.resume-editor-navigation')).toContainText('完成进度')
  await expect(page.locator('.resume-editor-navigation')).toContainText('专业证书')
  await expect(page.locator('.resume-editor-navigation')).not.toContainText('Completion')
  await expect(page.locator('#resume-basics')).toBeVisible()
  await expect(page.locator('#resume-work')).toBeHidden()

  await page.locator('.resume-editor-navigation').getByRole('button', { name: /工作经历/ }).click()
  await expect(page.locator('#resume-work')).toBeVisible()
  await expect(page.locator('#resume-basics')).toBeHidden()
  await page.evaluate(() => window.scrollTo(0, 300))
  const scrollBeforePreview = await page.evaluate(() => window.scrollY)

  await page.getByRole('button', { name: '预览简历' }).click()
  await expect(page.locator('.resume-preview-workspace')).toBeVisible()
  await expect(page.locator('.studio-grid')).toHaveCount(0)
  await expect(page.locator('.resume-editor-navigation')).toHaveCount(0)
  await expect(page.locator('.resume-preview-workspace .resume-paper')).toBeVisible()
  await expect(page.locator('.resume-preview-workspace .resume-paper')).toHaveJSProperty('offsetWidth', 860)
  await expect(page.getByRole('button', { name: '返回编辑' })).toBeFocused()
  await page.locator('.preview-toolbar-actions').getByRole('button', { name: '选择模板' }).click()
  await page.getByRole('dialog', { name: '选择简历模板' }).getByRole('button', { name: /现代/ }).click()
  await page.getByRole('dialog', { name: '选择简历模板' }).getByRole('button', { name: '关闭' }).click()
  await expect(page.locator('.resume-preview-workspace .resume-paper')).toHaveClass(/template-modern/)

  await page.getByRole('button', { name: '返回编辑' }).click()
  await expect(page.locator('.resume-editor-navigation')).toBeVisible()
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(scrollBeforePreview)
  await expect(page.locator('#resume-work')).toBeVisible()

  await page.getByRole('button', { name: 'EN' }).click()
  await expect(page.locator('.resume-editor-navigation')).toContainText('Completion')
  await expect(page.locator('.resume-editor-navigation').getByRole('button', { name: 'Design & advanced' })).toBeVisible()
  await expect(page.locator('.resume-editor-navigation')).not.toContainText('完成进度')
})

test('reports one page and gives an in-context action for an otherwise empty preview', async ({ page }) => {
  await mockAuthenticatedApi(page)
  const editorVersion = {
    ...version,
    resumeJson: {
      basics: { name: 'Alice', title: 'Engineer' },
      work: [], education: [], skills: [], projects: [], certificates: [], languages: [],
      template: { code: 'classic' },
    },
  }
  await page.route('**/api/resume-versions/11', route => route.fulfill({ json: response(editorVersion) }))

  await page.setViewportSize({ width: 942, height: 720 })
  await page.goto('/resumes/1/edit')
  await page.getByRole('button', { name: '预览简历' }).click()
  await expect(page.locator('.preview-page-meta')).toContainText('预计 1 页 · A4')
  await expect(page.locator('.paper-empty-guide')).toContainText('返回编辑后开始填写')
})

test('offers to restore a locally saved resume editor draft', async ({ page }) => {
  await mockAuthenticatedApi(page)
  const editorVersion = {
    ...version,
    resumeJson: { basics: { name: 'Alice', title: 'Engineer' }, work: [], education: [], skills: [], projects: [], certificates: [], languages: [], template: { code: 'classic' } },
  }
  await page.route('**/api/resume-versions/11', route => route.fulfill({ json: response(editorVersion) }))

  await page.goto('/resumes/1/edit')
  await page.getByLabel('姓名').fill('Alice Draft')
  await page.waitForTimeout(700)
  await page.reload()
  await expect(page.getByRole('dialog', { name: '恢复未保存的草稿？' })).toBeVisible()
  await page.getByRole('button', { name: '恢复草稿' }).click()
  await expect(page.getByLabel('姓名')).toHaveValue('Alice Draft')
})

test('switches the application chrome and answer library between Chinese and English', async ({ page }) => {
  await mockAuthenticatedApi(page)

  await page.goto('/interview-assets')
  await expect(page.getByRole('heading', { name: '面试答案资产' })).toBeVisible()

  await page.getByRole('button', { name: 'EN' }).click()

  await expect(page.getByRole('button', { name: 'Prepare evidence' })).toBeVisible()
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

test('resumes the pending job generation after granting AI consent', async ({ page }) => {
  await mockAuthenticatedApi(page)
  const material = {
    id: 55,
    materialType: 'PROJECT_EXPERIENCE',
    title: 'Order platform modernization',
    usagePreference: 'PREFERRED',
    updatedAt: now,
  }
  const grantedConsent = {
    id: 9,
    status: 'GRANTED',
    createdAt: now,
    policyVersion: 'v1.2.0',
    providerCode: 'bailian',
    taskScopes: [
      'JOB_MATERIAL_SELECTION', 'JOB_GENERATION', 'RESUME_OPTIMIZE', 'ACHIEVEMENT_GUIDANCE',
      'COMMUNICATION_GENERATE', 'MATERIAL_IMPORT', 'INLINE_OPTIMIZE', 'INTERVIEW_COACH', 'ATS_ANALYSIS',
    ],
    dataCategories: ['RESUME', 'INTERVIEW_ANSWER', 'CAREER_MATERIAL', 'JOB_DESCRIPTION', 'PERSONAL_PROFILE'],
  }
  let consentGranted = false
  let generationPayload: unknown

  await page.route('**/api/career-materials', route => route.fulfill({ json: response([material]) }))
  await page.route('**/api/ai/consent', async route => {
    if (route.request().method() === 'POST') {
      consentGranted = true
      return route.fulfill({ json: response(grantedConsent) })
    }
    return route.fulfill({ json: response(consentGranted ? grantedConsent : null) })
  })
  await page.route('**/api/ai/select-materials-for-job', route => {
    generationPayload = route.request().postDataJSON()
    return route.fulfill({
      json: response({
        id: 99,
        taskType: 'JOB_MATERIAL_SELECTION',
        jobDescriptionId: job.id,
        status: 'PENDING',
        confirmationStatus: 'PENDING',
        resultJson: null,
        errorMessage: null,
        retryCount: 0,
        resultResumeVersionId: null,
        createdAt: now,
        updatedAt: now,
      }),
    })
  })

  await page.goto(`/generate?jdId=${job.id}`)
  await page.getByRole('button', { name: '下一步：选择资料' }).click()
  await page.getByRole('button', { name: '必须使用' }).click()
  await page.getByRole('button', { name: '下一步：开始生成' }).click()
  await page.getByRole('button', { name: '开始 AI 选材' }).click()

  await expect(page).toHaveURL(/\/ai-consent\?redirect=/)
  await page.getByRole('button', { name: '同意并启用 AI' }).click()

  await expect.poll(() => generationPayload).toMatchObject({
    jobDescriptionId: job.id,
    resumeTitle: `${job.companyName} - ${job.title}`,
    includedMaterialIds: [material.id],
    preferredMaterialIds: [],
    excludedMaterialIds: [],
  })
  await expect(page).toHaveURL(/\/generate\/materials\?taskId=99$/)
})

test('reviews AI material selection before starting resume generation', async ({ page }) => {
  await mockAuthenticatedApi(page)
  let confirmationPayload: unknown
  await page.route('**/api/ai/tasks/99/confirm-materials', route => {
    confirmationPayload = route.request().postDataJSON()
    return route.fulfill({ json: response({
      id: 100, taskType: 'JOB_GENERATION', parentTaskId: 99, jobDescriptionId: job.id,
      status: 'PENDING', confirmationStatus: 'PENDING', resultJson: null, errorMessage: null,
      retryCount: 0, resultResumeVersionId: null, createdAt: now, updatedAt: now,
    }) })
  })
  await page.route('**/api/ai/tasks/99', route => route.fulfill({ json: response({
    id: 99, taskType: 'JOB_MATERIAL_SELECTION', jobDescriptionId: job.id,
    status: 'SUCCESS', confirmationStatus: 'PENDING', errorMessage: null, updatedAt: now,
    resultJson: {
      recommended: [{ materialId: 55, title: '订单平台重构', materialType: 'PROJECT_EXPERIENCE', reason: '直接匹配高并发系统经验', matchedRequirements: ['Java 高并发'] }],
      unselected: [{ materialId: 56, title: 'AWS 证书', materialType: 'CERTIFICATE', reason: '与核心职责关联较弱' }],
      excluded: [{ materialId: 57, title: '内部创新奖', materialType: 'AWARD', usagePreference: 'EXCLUDED', exclusionReason: 'GLOBAL' }],
      missingRequirements: ['大型团队管理经验'],
    },
  }) }))

  await page.goto('/generate/materials?taskId=99')
  await expect(page.getByRole('heading', { name: '确认用于这份简历的资料' })).toBeVisible()
  await expect(page.getByText('直接匹配高并发系统经验')).toBeVisible()
  await expect(page.getByText('大型团队管理经验')).toBeVisible()
  await expect(page.locator('.selection-page')).not.toContainText('materialId')

  await page.getByRole('button', { name: '加入', exact: true }).click()
  await page.getByRole('button', { name: '本次强制加入' }).click()
  await page.getByRole('button', { name: '确认选材并生成简历' }).click()
  await expect.poll(() => confirmationPayload).toMatchObject({
    selectedMaterialIds: [55, 56, 57],
    forcedIncludedMaterialIds: [57],
  })
  await expect(page).toHaveURL(/\/generate\/confirm\?taskId=100$/)
})

test('renders the generated resume draft as readable fields instead of JSON', async ({ page }) => {
  await mockAuthenticatedApi(page)
  await page.route('**/api/ai/tasks/33', route => route.fulfill({
    json: response({
      id: 33,
      taskType: 'JOB_GENERATION',
      jobDescriptionId: job.id,
      status: 'SUCCESS',
      confirmationStatus: 'PENDING',
      errorMessage: null,
      updatedAt: now,
      resultJson: {
        draftResumeJson: {
          basics: { name: 'BYNeko', title: '高级后端工程师', summary: '专注高并发交易与平台工程。' },
          work: [{
            company: '星河科技',
            position: '高级后端工程师',
            period: '2021 年至今',
            description: '负责交易和账户平台。',
            highlights: ['将核心接口 P99 降至 160ms', '季度故障数下降 45%'],
            _source: 'materialId=62, type=WORK_EXPERIENCE',
          }],
          objective: { summary: '负责复杂后端系统的技术负责人角色。' },
          volunteering: [{ organization: '开源社区', role: '维护者', _sources: [{ materialId: 63 }] }],
          courses: [{ name: '高并发系统设计', provider: '示例技术学院', _sources: [{ materialId: 64 }] }],
          publications: [{ title: '可靠分布式系统实践', publisher: '技术社区', _sources: [{ materialId: 65 }] }],
          customSections: [{
            title: '领导力经历',
            entries: [{ name: '平台迁移领导力', description: '协调跨职能团队完成迁移。', _sources: [{ materialId: 66 }] }],
            _sources: [{ materialId: 66 }],
          }],
        },
        selected: [],
        unselected: [],
        missing: [],
        qualitySummary: {
          totalDraftItems: 2,
          sourcedItems: 1,
          pendingItems: 0,
          unsupportedItems: 1,
          draftGapCount: 0,
          missingRequirementCount: 0,
          readiness: 'REQUIRES_ACTION',
        },
        warnings: [],
      },
    }),
  }))

  await page.goto('/generate/confirm?taskId=33')
  const draft = page.locator('.draft-container')
  const workspace = page.locator('.review-workspace')
  const basicsSection = page.locator('.draft-section').filter({ hasText: '个人概要' })
  await expect(workspace).toBeVisible()
  await expect(page.getByRole('navigation', { name: '草稿章节' })).toBeVisible()
  await expect(page.locator('.draft-section')).toHaveCount(1)
  await expect(basicsSection.getByText('姓名', { exact: true })).toBeVisible()
  await expect(basicsSection.getByText('BYNeko', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: /工作经历/ }).click()
  await expect(draft.getByText('公司', { exact: true })).toBeVisible()
  await expect(draft).toContainText('将核心接口 P99 降至 160ms')
  await expect(draft).not.toContainText('work[0]')
  await expect(draft).not.toContainText('"company"')
  await expect(draft).not.toContainText('materialId=62')
  await page.getByRole('button', { name: /求职目标/ }).click()
  await expect(draft.getByRole('heading', { name: '求职目标' })).toBeVisible()
  await page.getByRole('button', { name: /志愿经历/ }).click()
  await expect(draft.getByRole('heading', { name: '志愿经历' })).toBeVisible()
  await page.getByRole('button', { name: /课程培训/ }).click()
  await expect(draft.getByRole('heading', { name: '课程培训' })).toBeVisible()
  await page.getByRole('button', { name: /研究成果/ }).click()
  await expect(draft.getByRole('heading', { name: '研究成果' })).toBeVisible()
  await page.getByRole('button', { name: /自定义模块/ }).click()
  await expect(draft.getByRole('heading', { name: '自定义模块' })).toBeVisible()
  await expect(draft).toContainText('平台迁移领导力')
  const qualitySummary = draft.locator('.quality-summary')
  await expect(qualitySummary.getByRole('heading', { name: '草稿质量摘要' })).toBeVisible()
  await expect(qualitySummary).toContainText('需要处理')
  await expect(qualitySummary).toContainText('待核实内容')

  await page.getByRole('button', { name: /工作经历/ }).click()
  const workSection = page.locator('.draft-section').filter({ hasText: '工作经历' })
  await expect(workSection.locator('.action-btn')).toHaveCount(3)
  await expect(workSection.getByRole('button', { name: '接受' })).toHaveCSS('white-space', 'nowrap')
  await workSection.getByRole('button', { name: '编辑' }).click()
  const dialog = page.getByRole('dialog', { name: '编辑内容' })
  await expect(dialog.getByText('公司', { exact: true })).toBeVisible()
  await expect(dialog.getByRole('textbox', { name: '公司' })).toHaveValue('星河科技')
  await expect(dialog).not.toContainText('"company"')
  await dialog.getByRole('textbox', { name: '经历描述' }).fill('负责核心交易和账户平台。')
  await dialog.getByRole('button', { name: '保存' }).click()
  await expect(workSection).toContainText('负责核心交易和账户平台。')

  await page.getByRole('button', { name: /个人概要/ }).click()
  await basicsSection.getByRole('button', { name: '编辑' }).click()
  const basicsDialog = page.getByRole('dialog', { name: '编辑内容' })
  await expect(basicsDialog.getByRole('textbox', { name: '邮箱' })).toBeVisible()
  await expect(page.locator('.confirm-actions')).toBeVisible()
  await basicsDialog.getByRole('button', { name: '关闭' }).click()
  await expect(basicsDialog).toBeHidden()

  await page.setViewportSize({ width: 390, height: 844 })
  const mobileOutline = page.locator('.mobile-outline-trigger')
  await expect(mobileOutline).toBeVisible()
  await mobileOutline.click()
  const mobileNavigation = page.getByRole('navigation', { name: '草稿章节' })
  await expect(mobileNavigation).toBeVisible()
  await mobileNavigation.getByRole('button', { name: /工作经历/ }).click()
  await expect(draft.getByRole('heading', { name: '工作经历' })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})

test('moves through only the generated draft sections that still need attention', async ({ page }) => {
  await mockAuthenticatedApi(page)
  await page.route('**/api/ai/tasks/34', route => route.fulfill({
    json: response({
      id: 34,
      taskType: 'JOB_GENERATION',
      jobDescriptionId: job.id,
      status: 'SUCCESS',
      confirmationStatus: 'PENDING',
      errorMessage: null,
      updatedAt: now,
      resultJson: {
        draftResumeJson: {
          basics: { name: 'BYNeko', summary: '平台工程师。', _source: 'profile' },
          work: [{ company: '星河科技', position: '后端工程师', _pending: { reason: '需要确认任职时间' } }],
        },
        selected: [],
        unselected: [],
        missing: [{ section: 'education', reason: '未提供教育背景资料' }],
        qualitySummary: {
          totalDraftItems: 2,
          sourcedItems: 1,
          pendingItems: 1,
          unsupportedItems: 0,
          draftGapCount: 1,
          missingRequirementCount: 1,
          readiness: 'REQUIRES_ACTION',
        },
        warnings: [],
      },
    }),
  }))

  await page.goto('/generate/confirm?taskId=34')
  const draft = page.locator('.draft-container')
  const confirmButton = draft.getByRole('button', { name: /确认并创建简历/ })
  await expect(draft.getByRole('heading', { name: '工作经历' })).toBeVisible()
  await expect(confirmButton).toBeDisabled()

  await draft.getByRole('button', { name: /只看待处理/ }).click()
  const attentionNavigation = draft.getByRole('navigation', { name: '草稿章节' })
  await expect(attentionNavigation.locator('.section-navigation__item')).toHaveCount(2)
  await draft.getByRole('button', { name: '下一章节' }).click()
  await expect(draft.getByRole('heading', { name: '教育背景' })).toBeVisible()
  await draft.getByRole('button', { name: '上一章节' }).click()
  await expect(draft.getByRole('heading', { name: '工作经历' })).toBeVisible()
  await draft.getByRole('button', { name: '接受' }).click()

  await expect(attentionNavigation.locator('.section-navigation__item')).toHaveCount(1)
  await expect(attentionNavigation.getByRole('button', { name: /工作经历/ })).toHaveCount(0)
  await expect(draft.getByRole('heading', { name: '教育背景' })).toBeVisible()
  await expect(draft.getByText('未提供教育背景资料')).toBeVisible()
  await expect(draft.getByText('该章节暂无可审核草稿，可根据资料缺口稍后补充。')).toBeVisible()
  await expect(confirmButton).toBeEnabled()
})

test('generates an editable communication draft and carries it into an application', async ({ page }) => {
  await mockAuthenticatedApi(page)
  let generationPayload: unknown
  await page.route('**/api/communications/generate', async route => {
    generationPayload = route.request().postDataJSON()
    await route.fulfill({ json: response({ type: 'EMAIL', draft: 'Hello Example Systems, I would like to apply.', sentAutomatically: false, requiresManualConfirmation: true, generationSource: 'TEMPLATE' }) })
  })
  await page.goto('/communications')
  const versionSelect = page.locator('select').nth(1)
  await expect(versionSelect).toBeEnabled()
  await expect(versionSelect.locator('option[value="11"]')).toHaveCount(1)
  await versionSelect.selectOption('11')
  await page.locator('select').nth(2).selectOption('20')
  await page.locator('select').nth(3).selectOption('EMAIL')
  const communicationForm = page.locator('form.compact-form')
  await communicationForm.locator('.generation-actions button').nth(1).click()
  await expect.poll(() => generationPayload).toEqual({ resumeVersionId: 11, jobDescriptionId: 20, type: 'EMAIL', outputLanguage: 'ZH_CN' })
  const editor = page.locator('article.workspace-card textarea')
  await editor.fill('Edited email body')
  // 草稿卡片 job-actions 的主按钮是「用于投递」，与按钮顺序解耦（避免新增按钮导致索引漂移）
  await page.locator('article.workspace-card .job-actions .btn-primary').click()
  await expect(page).toHaveURL(/\/applications$/)
  await expect(page.locator('textarea').nth(1)).toHaveValue('Edited email body')
})

test('generates a communication draft through the AI task lifecycle', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await mockAuthenticatedApi(page)
  let aiPayload: unknown
  await page.route('**/api/communications/ai-generate', async route => {
    aiPayload = route.request().postDataJSON()
    expect(route.request().headers()['idempotency-key']).toMatch(/^communication:[0-9a-f-]{36}$/)
    await route.fulfill({ json: response({
      id: 81, taskType: 'COMMUNICATION_GENERATE', jobDescriptionId: 20, status: 'PENDING',
      resultJson: null, errorMessage: null, retryCount: 0, createdAt: now, updatedAt: now,
    }) })
  })
  await page.route('**/api/ai/tasks/81', route => route.fulfill({ json: response({
    id: 81, taskType: 'COMMUNICATION_GENERATE', jobDescriptionId: 20, status: 'SUCCESS',
    resultJson: {
      type: 'EMAIL', subject: '申请后端工程师岗位', body: '您好，我具备 Java 服务开发经验。',
      draft: '主题：申请后端工程师岗位\n\n您好，我具备 Java 服务开发经验。', generationSource: 'AI',
      communicationDraftId: 91, resumeVersionId: 11, jobDescriptionId: 20, promptVersion: 'communication-v1',
    },
    errorMessage: null, retryCount: 0, createdAt: now, updatedAt: now,
  }) }))

  await page.goto('/communications')
  await page.locator('select').nth(1).selectOption('11')
  await page.locator('select').nth(2).selectOption('20')
  await page.locator('select').nth(3).selectOption('EMAIL')
  await page.locator('form.compact-form .generation-actions button').first().click()

  await expect.poll(() => aiPayload).toEqual({
    resumeVersionId: 11, jobDescriptionId: 20, type: 'EMAIL', outputLanguage: 'ZH_CN',
  })
  await expect(page).toHaveURL(/taskId=81/)
  await expect(page.getByText('AI 生成', { exact: true })).toBeVisible({ timeout: 5_000 })
  await expect(page.locator('article.workspace-card textarea')).toHaveValue(/Java 服务开发经验/)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
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
  await expect(page.getByText(/等待开始导出|正在生成 PDF/)).toBeVisible()
  await expect(page.getByText('PDF 已准备好')).toBeVisible()
  const download = page.waitForEvent('download')
  await actions.first().click()
  expect((await download).suggestedFilename()).toBe('resume.pdf')
})

test('restores an authenticated session after a page refresh', async ({ page }) => {
  let refreshCount = 0
  await page.route('**/api/auth/refresh', route => {
    refreshCount += 1
    return route.fulfill({ json: response({ accessToken: `refresh-token-${refreshCount}` }) })
  })
  await page.route('**/api/auth/me', route => route.fulfill({ json: response({ id: 99, username: 'e2e-user', email: 'e2e@example.com' }) }))
  await page.route('**/api/resumes', route => route.fulfill({ json: response([resume]) }))

  await page.goto('/resumes')
  await expect(page).toHaveURL(/\/resumes$/)
  await expect(page.getByText(resume.title)).toBeVisible()

  await page.reload()
  await expect(page).toHaveURL(/\/resumes$/)
  await expect(page.getByText(resume.title)).toBeVisible()
  await expect.poll(() => refreshCount).toBe(2)
})

test('archives a historical version and restores it to the visible history', async ({ page }) => {
  const activeVersions = [
    version,
    { ...version, id: 12, versionNo: 2, sourceType: 'MANUAL', archivedAt: null, restoredFromVersionId: null },
  ]
  const archivedVersions = [{ ...activeVersions[1], archivedAt: now }]
  let archived = false
  let archiveCalls = 0
  let unarchiveCalls = 0

  await mockAuthenticatedApi(page)
  await page.route('**/api/resumes/1/versions**', route => {
    const url = new URL(route.request().url())
    if (route.request().method() === 'POST' && url.pathname.endsWith('/12/archive')) {
      archived = true
      archiveCalls += 1
      return route.fulfill({ json: response(null) })
    }
    if (route.request().method() === 'POST' && url.pathname.endsWith('/12/unarchive')) {
      archived = false
      unarchiveCalls += 1
      return route.fulfill({ json: response(null) })
    }
    const requestedArchived = url.searchParams.get('archived') === 'true'
    return route.fulfill({ json: response(requestedArchived ? (archived ? archivedVersions : []) : (archived ? [activeVersions[0]] : activeVersions)) })
  })
  page.on('dialog', dialog => dialog.accept())

  await page.goto('/resumes/1')
  const historicalCard = page.locator('.version-card').filter({ hasText: 'v2' })
  await expect(historicalCard).toBeVisible()
  // 用可访问名称定位「归档 v2」，避免新增操作按钮（恢复/对比）导致索引漂移
  await historicalCard.getByRole('button', { name: '归档 v2' }).click()
  await expect.poll(() => archiveCalls).toBe(1)
  await expect(historicalCard).toHaveCount(0)

  await page.locator('.version-history-tabs button').nth(1).click()
  const archivedCard = page.locator('.version-card').filter({ hasText: 'v2' })
  await expect(archivedCard).toBeVisible()
  await archivedCard.locator('button').first().click()
  await expect.poll(() => unarchiveCalls).toBe(1)
  await expect(archivedCard).toHaveCount(0)
})

test('opens the resume preview as a standalone mobile workspace', async ({ page }) => {
  await mockAuthenticatedApi(page)
  const editorVersion = {
    ...version,
    resumeJson: {
      basics: { name: 'Mobile Candidate', title: 'Engineer', summary: 'Builds reliable systems.' },
      work: [], education: [], skills: [], projects: [], certificates: [], languages: [], template: { code: 'classic' },
    },
  }
  await page.route('**/api/resume-versions/11', route => route.fulfill({ json: response(editorVersion) }))
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/resumes/1/edit')

  await expect(page.locator('.resume-editor-navigation')).toBeVisible()
  await page.getByRole('button', { name: '预览', exact: true }).click()
  await expect(page.locator('.resume-preview-workspace')).toBeVisible()
  await expect(page.locator('.studio-grid')).toHaveCount(0)
  await expect(page.locator('.resume-preview-workspace .resume-paper')).toBeVisible()
  await expect(page.locator('.resume-preview-workspace')).not.toHaveCSS('overflow-x', 'scroll')
  await page.getByRole('button', { name: '返回编辑' }).click()
  await expect(page.locator('.resume-preview-workspace')).toHaveCount(0)
  await expect(page.locator('#resume-basics')).toBeVisible()
})

// ---- 002 fix plan U1/U2/U4: generated time ranges survive confirmation and preview ----

test('keeps legacy period-only time ranges visible in the editor preview', async ({ page }) => {
  await mockAuthenticatedApi(page)
  const editorVersion = {
    ...version,
    resumeJson: {
      basics: { name: 'Alice', title: 'Engineer' },
      work: [
        { company: 'Legacy Systems', position: 'Engineer', period: '2021 年至今' },
        { company: 'Modern Systems', position: 'Engineer', startDate: '2019-01', endDate: '2021-06', period: '2019 - 2021' },
      ],
      projects: [{ name: 'Order Platform', role: 'Lead', period: '2023 Q2 - 2023 Q4' }],
      education: [{ school: 'Legacy University', degree: 'BSc', period: '2018 - 2022' }],
      skills: [], certificates: [], awards: [], languages: [], template: { code: 'classic' },
    },
  }
  await page.route('**/api/resume-versions/11', route => route.fulfill({ json: response(editorVersion) }))

  await page.goto('/resumes/1/edit')
  await page.getByRole('button', { name: '预览', exact: true }).click()
  const paper = page.locator('.resume-preview-workspace .resume-paper')
  await expect(paper).toContainText('2021 年至今')
  await expect(paper).toContainText('2019-01 — 2021-06')
  await expect(paper).not.toContainText('2019 - 2021')
  await expect(paper).toContainText('2023 Q2 - 2023 Q4')
  await expect(paper).toContainText('2018 - 2022')
})

test('carries a generated period-only draft through confirmation into the editor preview', async ({ page }) => {
  await mockAuthenticatedApi(page)
  const confirmedWork = { company: '星河科技', position: '高级后端工程师', period: '2021 年至今', description: '负责交易和账户平台。', highlights: ['将核心接口 P99 降至 160ms'] }
  await page.route('**/api/resumes/by-jd/**', route => route.fulfill({ json: response([]) }))
  await page.route('**/api/ai/tasks/35', route => route.fulfill({
    json: response({
      id: 35,
      taskType: 'JOB_GENERATION',
      jobDescriptionId: job.id,
      status: 'SUCCESS',
      confirmationStatus: 'PENDING',
      errorMessage: null,
      updatedAt: now,
      resultJson: {
        draftResumeJson: {
          basics: { name: 'BYNeko', title: '高级后端工程师' },
          work: [{ ...confirmedWork, _source: 'materialId=62, type=WORK_EXPERIENCE' }],
          education: [{ school: '示例大学', degree: '本科', period: '2018 - 2022', _source: 'materialId=63, type=EDUCATION' }],
          projects: [{ name: '订单平台', role: '负责人', period: '2023 Q2 - 2023 Q4', _source: 'materialId=64, type=PROJECT_EXPERIENCE' }],
        },
        selected: [],
        unselected: [],
        missing: [],
        qualitySummary: {
          totalDraftItems: 3, sourcedItems: 3, pendingItems: 0, unsupportedItems: 0,
          draftGapCount: 0, missingRequirementCount: 0, readiness: 'READY',
        },
        warnings: [],
      },
    }),
  }))
  await page.route('**/api/resume-versions/11', route => route.fulfill({
    json: response({
      ...version,
      resumeJson: {
        basics: { name: 'BYNeko', title: '高级后端工程师' },
        work: [confirmedWork],
        education: [{ school: '示例大学', degree: '本科', period: '2018 - 2022' }],
        projects: [{ name: '订单平台', role: '负责人', period: '2023 Q2 - 2023 Q4' }],
        skills: [], certificates: [], awards: [], languages: [], template: { code: 'classic' },
      },
    }),
  }))
  let confirmPayload: unknown = null
  await page.route('**/api/ai/tasks/35/confirm', async route => {
    confirmPayload = route.request().postDataJSON()
    await route.fulfill({ json: response({ resumeId: 1, resumeVersionId: 12, versionNo: 2, resultResumeVersionId: 12, rejectedPaths: [], newMaterialIds: [] }) })
  })

  await page.goto('/generate/confirm?taskId=35')
  await expect(page.locator('.draft-container')).toBeVisible()
  await page.getByRole('button', { name: /工作经历/ }).click()
  await expect(page.locator('.draft-section')).toContainText('2021 年至今')
  await page.getByRole('button', { name: '确认并创建简历' }).click()
  await expect(page).toHaveURL(/\/resumes\/1$/)
  expect(confirmPayload).not.toBeNull()

  await page.goto('/resumes/1/edit')
  await page.getByRole('button', { name: '预览', exact: true }).click()
  const paper = page.locator('.resume-preview-workspace .resume-paper')
  await expect(paper).toContainText('2021 年至今')
  await expect(paper).toContainText('2018 - 2022')
  await expect(paper).toContainText('2023 Q2 - 2023 Q4')
  await expect(paper).not.toContainText('materialId=62')
})
