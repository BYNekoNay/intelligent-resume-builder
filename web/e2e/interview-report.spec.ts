import { expect, test, type Page } from '@playwright/test'

/**
 * InterviewView 报告逐轮明细 E2E（O-04）。
 *
 * 覆盖诊断盲区：报告 rounds 展开详情、保存答案资产的幂等态（保存后按钮变为
 * 「Saved」，重复点击不产生重复请求）。全部走可控路由 mock。
 */
const response = (data: unknown) => ({ code: 0, message: 'ok', data, traceId: 'interview-report-e2e' })
const now = '2026-08-16T10:00:00Z'

const resume = { id: 1, title: 'Backend resume', currentVersionId: 11, jobDescriptionId: 20, createdAt: now, updatedAt: now }
const version = { id: 11, resumeId: 1, versionNo: 1, sourceType: 'MANUAL', resumeJson: null, optimizationSummary: null, createdAt: now, archivedAt: null, restoredFromVersionId: null, generationContext: null }
const job = { id: 20, title: 'Backend Engineer', companyName: 'Example Systems', jdText: 'Java and Spring Boot', parsedKeywordsJson: null, parsedAt: null, parsedVersion: null, createdAt: now, updatedAt: now }

const completedState = {
  interviewId: 5,
  status: 'COMPLETED',
  executionMode: 'AI',
  currentQuestion: null,
  currentQuestionNo: null,
  completedQuestionCount: 2,
  targetQuestionCount: 4,
  minQuestionCount: 2,
  maxQuestionCount: 6,
  lastEvaluation: null,
  aiFailure: null,
  completionReason: 'USER_FINISHED',
  sourceType: 'PLATFORM_RESUME',
  resumeVersionId: 11,
  jobDescriptionId: 20,
}

const report = {
  interviewId: 5,
  totalScore: 78,
  summary: 'Strong technical evidence with clear structure.',
  strengths: ['Clear evidence', 'Structured answers'],
  weaknesses: ['Evidence specificity'],
  resumeSuggestions: ['Add scale metrics'],
  expressionSuggestions: ['Pause before answering'],
  dimensionScores: { relevance: 20, evidenceSpecificity: 18, structureClarity: 16, roleCompetency: 16, authenticityReflection: 8 },
  targetQuestionCount: 4,
  actualQuestionCount: 2,
  completionReason: 'USER_FINISHED',
  evaluationSource: 'MIXED',
  aiEvaluatedRounds: 1,
  ruleEvaluatedRounds: 1,
  rounds: [
    {
      recordId: 101, roundNo: 1, questionText: 'Describe your Java experience.', answerText: 'Built Java services.',
      roundScore: 80, dimensionScores: null, strengths: ['Clear'], improvements: ['Add detail'],
      suggestedAnswer: 'A more structured Java answer.', evaluationSource: 'AI',
    },
    {
      recordId: 102, roundNo: 2, questionText: 'How do you ensure quality?', answerText: 'Code review.',
      roundScore: 76, dimensionScores: null, strengths: [], improvements: ['Add evidence'],
      suggestedAnswer: null, evaluationSource: 'RULE',
    },
  ],
}

async function mockInterviewReportPage(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('intelligent-resume.locale', 'en-US')
    sessionStorage.setItem('interview-session-id', '5')
  })
  await page.route('**/api/auth/refresh', route => route.fulfill({ json: response({ accessToken: 'interview-token' }) }))
  await page.route('**/api/auth/me', route => route.fulfill({ json: response({ id: 1, username: 'interview-user', email: 'interview@example.com' }) }))
  await page.route('**/api/resumes', route => route.fulfill({ json: response([resume]) }))
  await page.route('**/api/resumes/1/versions**', route => route.fulfill({ json: response([version]) }))
  await page.route('**/api/jobs', route => route.fulfill({ json: response([job]) }))
  await page.route('**/api/interviews/5', route => route.fulfill({ json: response(completedState) }))
  await page.route('**/api/interviews/5/report', route => route.fulfill({ json: response(report) }))
  await page.route('**/api/interview-answer-assets', async route => {
    if (route.request().method() === 'POST') {
      const body = route.request().postDataJSON() as { interviewRecordId: number; questionText: string }
      await route.fulfill({ json: response({
        id: 200 + body.interviewRecordId, interviewRecordId: body.interviewRecordId,
        questionText: body.questionText, originalAnswerText: 'Built Java services.',
        suggestedAnswerText: 'A more structured Java answer.', feedbackJson: null,
        createdAt: now, updatedAt: now, sectionKeys: ['work'], materialIds: [],
      }) })
      return
    }
    await route.fulfill({ json: response([]) })
  })
}

test('expands report rounds and saves an answer asset with an idempotent saved state', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await mockInterviewReportPage(page)
  let assetCreates = 0
  page.on('request', request => {
    if (request.method() === 'POST' && request.url().endsWith('/api/interview-answer-assets')) assetCreates += 1
  })

  await page.goto('/interviews')

  // 报告自动加载（会话恢复为 COMPLETED）
  await expect(page.getByRole('heading', { name: 'Interview report' })).toBeVisible({ timeout: 10_000 })
  await expect(page.getByText('Strong technical evidence with clear structure.')).toBeVisible()

  // rounds 默认折叠：展开第 1 轮详情
  const roundCard = page.locator('.round-card').filter({ hasText: 'Describe your Java experience.' })
  await expect(roundCard.locator('.round-body')).toBeHidden()
  await roundCard.locator('summary').click()
  await expect(roundCard.locator('.round-body')).toBeVisible()
  await expect(roundCard.locator('.round-body')).toContainText('A more structured Java answer.')

  // 保存答案资产 → 按钮变为 Saved（幂等态）
  const saveButton = roundCard.getByRole('button', { name: 'Save as answer asset' })
  await saveButton.click()
  await expect(roundCard.getByText('Saved', { exact: true })).toBeVisible()
  await expect(roundCard.getByRole('button', { name: 'Save as answer asset' })).toHaveCount(0)
  expect(assetCreates).toBe(1)

  // 第 2 轮未保存：展开后仍显示保存按钮；再次折叠/展开第 1 轮不会重复创建资产
  const round2Card = page.locator('.round-card').filter({ hasText: 'How do you ensure quality?' })
  await round2Card.locator('summary').click()
  await expect(round2Card.getByRole('button', { name: 'Save as answer asset' })).toBeVisible()
  await roundCard.locator('summary').click()
  await roundCard.locator('summary').click()
  await expect(roundCard.getByText('Saved', { exact: true })).toBeVisible()
  expect(assetCreates).toBe(1)
})
