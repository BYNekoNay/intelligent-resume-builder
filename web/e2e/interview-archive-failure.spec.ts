import { expect, test, type Page } from '@playwright/test'

/**
 * 归档版本导致 AI 评估失败时的专属指引 E2E（第四十九批）。
 *
 * 覆盖诊断盲区：服务端对「归档版本被消费」返回专属业务码 `40902`
 * （`VERSION_ARCHIVED`），可操作处置是「先恢复该版本再重试」，与通用 AI 失败的
 * 「重试/降级」不同。前端**不透传服务端 message**（双语设计），若没有按码分支，
 * 用户只会看到泛化的「AI 服务暂时不可用」——恢复动作无处得知。本用例以路由 mock
 * 固化该指引的存在，并断言非归档失败**不得**出现该指引（避免文案泛化）。
 */
const response = (data: unknown) => ({ code: 0, message: 'ok', data, traceId: 'interview-archive-e2e' })
const now = '2026-08-16T10:00:00Z'

const resume = { id: 1, title: 'Backend resume', currentVersionId: 11, jobDescriptionId: 20, createdAt: now, updatedAt: now }
const version = { id: 11, resumeId: 1, versionNo: 1, sourceType: 'MANUAL', resumeJson: null, optimizationSummary: null, createdAt: now, archivedAt: null, restoredFromVersionId: null, generationContext: null }
const job = { id: 20, title: 'Backend Engineer', companyName: 'Example Systems', jdText: 'Java and Spring Boot', parsedKeywordsJson: null, parsedAt: null, parsedVersion: null, createdAt: now, updatedAt: now }

function failedState(messageCode: string) {
  return {
    interviewId: 5,
    status: 'AI_ACTION_REQUIRED',
    executionMode: 'AI',
    currentQuestion: 'Describe your Java experience.',
    currentQuestionNo: 1,
    completedQuestionCount: 0,
    targetQuestionCount: 4,
    minQuestionCount: 2,
    maxQuestionCount: 6,
    lastEvaluation: null,
    aiFailure: {
      operationId: 77,
      stage: 'ANSWER_EVALUATION',
      retryable: true,
      reauthorizationRequired: false,
      messageCode,
    },
    completionReason: null,
    sourceType: 'PLATFORM_RESUME',
    resumeVersionId: 11,
    jobDescriptionId: 20,
  }
}

async function mockInterviewFailure(page: Page, messageCode: string) {
  await page.addInitScript(() => {
    localStorage.setItem('intelligent-resume.locale', 'en-US')
    sessionStorage.setItem('interview-session-id', '5')
  })
  await page.route('**/api/auth/refresh', route => route.fulfill({ json: response({ accessToken: 'interview-token' }) }))
  await page.route('**/api/auth/me', route => route.fulfill({ json: response({ id: 1, username: 'interview-user', email: 'interview@example.com' }) }))
  await page.route('**/api/resumes', route => route.fulfill({ json: response([resume]) }))
  await page.route('**/api/resumes/1/versions**', route => route.fulfill({ json: response([version]) }))
  await page.route('**/api/jobs', route => route.fulfill({ json: response([job]) }))
  await page.route('**/api/interviews/5', route => route.fulfill({ json: response(failedState(messageCode)) }))
}

test('archived resume version shows actionable restore guidance on the AI failure panel', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await mockInterviewFailure(page, 'VERSION_ARCHIVED')

  await page.goto('/interviews')

  const hint = page.locator('.ai-failure-archive-hint')
  await expect(hint).toBeVisible({ timeout: 10_000 })
  await expect(hint).toContainText('Restore it in Version history')
  // 可重试：恢复版本后即可继续本轮评估（服务端也保持 retryable=true）
  await expect(page.getByRole('button', { name: 'Retry AI' })).toBeVisible()
})

test('non-archive AI failure keeps the generic panel without archive guidance', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await mockInterviewFailure(page, 'AI_FAILURE')

  await page.goto('/interviews')

  await expect(page.locator('.ai-failure-header')).toBeVisible({ timeout: 10_000 })
  await expect(page.locator('.ai-failure-archive-hint')).toHaveCount(0)
})