import { expect, test, type Page, type Route } from '@playwright/test'

// R32：发送前预览（AC-R32-6 / AC-R32-7）。
//
// 这里守两件事 —— 都是"协议层有没有被真的执行"，不是"弹窗好不好看"：
//  1. **取消 = 一条都不发**：点取消后 /api/ai/tasks 一次都没被调用；
//  2. **勾选包含联系方式必须重新预览**：令牌是按脱敏后的载荷算的，不重预览就会 40901。

const response = (data: unknown) => ({ code: 0, message: 'ok', data, traceId: 'ai-preview-e2e' })

const previewResponse = (includeContact: boolean) => ({
  taskType: 'MATERIAL_IMPORT',
  includeContact,
  redactions: includeContact ? [] : [{ category: 'EMAIL', count: 1 }],
  previewToken: includeContact ? 'token-with-contact' : 'token-redacted',
  messages: [
    { role: 'system', content: 'You are a resume assistant.' },
    {
      role: 'user',
      content: includeContact ? '联系 alice@example.com，整理成简历。' : '联系 [EMAIL]，整理成简历。',
    },
  ],
})

interface Seen {
  previews: Array<Record<string, unknown>>
  creates: Array<Record<string, unknown>>
}

async function mockAuthenticatedApi(page: Page, seen: Seen) {
  await page.route('**/api/auth/refresh', route => route.fulfill({ json: response({ accessToken: 'e2e-token' }) }))
  await page.route('**/api/auth/me', route => route.fulfill({
    json: response({ id: 99, username: 'local-desktop', email: 'e2e@example.com', displayName: 'E2E' }),
  }))
  await page.route('**/api/resumes**', route => route.fulfill({ json: response([]) }))
  await page.route('**/api/ai/consent', route => route.fulfill({
    json: response({
      id: 1, status: 'GRANTED', createdAt: '2026-01-01T00:00:00', policyVersion: 'v1.2.0',
      providerCode: 'bailian',
      taskScopes: ['MATERIAL_IMPORT', 'RESUME_OPTIMIZE', 'INLINE_OPTIMIZE', 'JOB_GENERATION',
        'JOB_MATERIAL_SELECTION', 'ACHIEVEMENT_GUIDANCE', 'COMMUNICATION_GENERATE', 'INTERVIEW_COACH', 'ATS_ANALYSIS'],
      dataCategories: ['RESUME', 'JOB_DESCRIPTION', 'CAREER_MATERIAL', 'PERSONAL_PROFILE', 'INTERVIEW_ANSWER'],
    }),
  }))

  await page.route('**/api/ai/tasks/preview', async (route: Route) => {
    const body = route.request().postDataJSON() as Record<string, unknown>
    seen.previews.push(body)
    await route.fulfill({ json: response(previewResponse(Boolean(body.includeContact))) })
  })

  await page.route('**/api/ai/tasks', async (route: Route) => {
    if (route.request().method() !== 'POST') {
      await route.fulfill({ json: response(null) })
      return
    }
    seen.creates.push(route.request().postDataJSON() as Record<string, unknown>)
    await route.fulfill({ json: response({ id: 501, status: 'PENDING' }) })
  })

  // 轮询：让任务停在 PENDING，避免用例去等真实生成
  await page.route('**/api/ai/tasks/501', route => route.fulfill({ json: response({ id: 501, status: 'RUNNING', resultJson: null }) }))
}

/**
 * R32 第 5 片：生成确认页 / 沟通文案 / 追问练习三个入口共用同一条协议。
 *
 * 断言的是「协议有没有被真的执行」：取消 → 预览 1 次、创建 0 次（AC-R32-11）。
 * 路径顺序固定为「预览端点先注册、创建端点后注册」，避免通配 route 抢先匹配。
 */
const now = '2026-07-22T10:00:00Z'
const resume = { id: 1, title: 'Backend resume', currentVersionId: 11, jobDescriptionId: 20, createdAt: now, updatedAt: now }
const version = { id: 11, resumeId: 1, versionNo: 1, sourceType: 'MANUAL', resumeJson: null, optimizationSummary: null, createdAt: now, archivedAt: null, restoredFromVersionId: null, generationContext: null }
const job = { id: 20, title: 'Backend Engineer', companyName: 'Example Systems', jdText: 'Java and Spring Boot', parsedKeywordsJson: null, parsedAt: null, parsedVersion: null, createdAt: now, updatedAt: now }

const selectionTask = {
  id: 99, taskType: 'JOB_MATERIAL_SELECTION', jobDescriptionId: 20, status: 'SUCCESS',
  confirmationStatus: 'PENDING', errorMessage: null, updatedAt: now,
  resultJson: {
    recommended: [{ materialId: 55, title: '订单平台重构', materialType: 'PROJECT_EXPERIENCE', reason: '直接匹配高并发系统经验' }],
    unselected: [], excluded: [], missingRequirements: [],
  },
}

const interviewState = {
  interviewId: 5, status: 'COMPLETED', executionMode: 'AI', currentQuestion: null, currentQuestionNo: null,
  completedQuestionCount: 2, targetQuestionCount: 4, minQuestionCount: 2, maxQuestionCount: 6,
  lastEvaluation: null, aiFailure: null, completionReason: 'USER_FINISHED',
  sourceType: 'PLATFORM_RESUME', resumeVersionId: 11, jobDescriptionId: 20,
}

const interviewReport = {
  totalScore: 78, summary: 'Strong technical evidence.', strengths: ['Clear evidence'],
  weaknesses: ['Evidence specificity'], resumeSuggestions: [], expressionSuggestions: [],
  dimensionScores: null, targetQuestionCount: 4, actualQuestionCount: 2,
  completionReason: 'USER_FINISHED', evaluationSource: 'MIXED', aiEvaluatedRounds: 1,
  ruleEvaluatedRounds: 1, rounds: [],
}

async function mockSession(page: Page) {
  await page.route('**/api/auth/refresh', route => route.fulfill({ json: response({ accessToken: 'e2e-token' }) }))
  await page.route('**/api/auth/me', route => route.fulfill({
    json: response({ id: 99, username: 'local-desktop', email: 'e2e@example.com', displayName: 'E2E' }),
  }))
  await page.route('**/api/resumes', route => route.fulfill({ json: response([resume]) }))
  await page.route('**/api/resumes/1/versions**', route => route.fulfill({ json: response([version]) }))
  await page.route('**/api/jobs', route => route.fulfill({ json: response([job]) }))
  await page.route('**/api/interview-answer-assets**', route => route.fulfill({ json: response([]) }))
}

const MATERIAL_TEXT = '我在 2021 到 2024 年做后端开发，联系 alice@example.com。'

async function gotoMaterialGeneration(page: Page) {
  await page.goto('/material-generation')
  await page.getByRole('textbox').first().fill(MATERIAL_TEXT)
  await expect(page.getByRole('button', { name: /Generate with AI|AI 生成草稿/i })).toBeVisible()
}

test.describe('AI 发送前预览', () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => localStorage.setItem('intelligent-resume.locale', 'en-US'))
  })

  test('取消预览则一条任务都不发', async ({ page }) => {
    const seen: Seen = { previews: [], creates: [] }
    await mockAuthenticatedApi(page, seen)
    await gotoMaterialGeneration(page)

    await page.getByRole('button', { name: /Generate with AI/i }).click()

    const dialog = page.getByTestId('ai-preview-overlay')
    await expect(dialog).toBeVisible()
    // 预览里展示的就是脱敏后的内容
    await expect(page.getByTestId('ai-preview-messages')).toContainText('[EMAIL]')
    await expect(page.getByTestId('ai-preview-redactions')).toContainText('Email')

    await page.getByTestId('ai-preview-cancel').click()
    await expect(dialog).toBeHidden()

    expect(seen.previews).toHaveLength(1)
    expect(seen.creates, '取消后不得创建任务').toHaveLength(0)
  })

  test('勾选包含联系方式会重新预览，确认后带着新令牌提交', async ({ page }) => {
    const seen: Seen = { previews: [], creates: [] }
    await mockAuthenticatedApi(page, seen)
    await gotoMaterialGeneration(page)

    await page.getByRole('button', { name: /Generate with AI/i }).click()
    await expect(page.getByTestId('ai-preview-overlay')).toBeVisible()

    await page.getByTestId('ai-preview-contact-toggle').check()
    // 重新预览：第二次请求必须显式带 includeContact=true
    await expect.poll(() => seen.previews.length).toBe(2)
    expect(seen.previews[1].includeContact).toBe(true)
    await expect(page.getByTestId('ai-preview-messages')).toContainText('alice@example.com')

    await page.getByTestId('ai-preview-confirm').click()
    await expect.poll(() => seen.creates.length).toBe(1)

    // 提交的是**第二次预览**的令牌 —— 用第一次的会被后端 40901 拒绝
    const input = seen.creates[0].input as Record<string, unknown>
    expect(input._previewToken).toBe('token-with-contact')
  })
})

test.describe('选材 / 文案 / 追问三个入口的取消路径', () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => localStorage.setItem('intelligent-resume.locale', 'en-US'))
  })

  test('生成确认页：取消预览则 confirm-materials 一条都不发', async ({ page }) => {
    const seen: Seen = { previews: [], creates: [] }
    await mockSession(page)
    await page.route('**/api/ai/tasks/99', route => route.fulfill({ json: response(selectionTask) }))
    await page.route('**/api/ai/tasks/99/confirm-materials/preview', async (route: Route) => {
      seen.previews.push(route.request().postDataJSON() as Record<string, unknown>)
      await route.fulfill({ json: response(previewResponse(false)) })
    })
    await page.route('**/api/ai/tasks/99/confirm-materials', async (route: Route) => {
      seen.creates.push(route.request().postDataJSON() as Record<string, unknown>)
      await route.fulfill({ json: response({ id: 100, taskType: 'JOB_GENERATION', status: 'PENDING' }) })
    })

    await page.goto('/generate/materials?taskId=99')
    const confirmButton = page.getByRole('button', { name: /Confirm selection & generate resume/i })
    await expect(confirmButton).toBeVisible()
    await confirmButton.click()

    const dialog = page.getByTestId('ai-preview-overlay')
    await expect(dialog).toBeVisible()
    await page.getByTestId('ai-preview-cancel').click()
    await expect(dialog).toBeHidden()

    expect(seen.previews).toHaveLength(1)
    expect(seen.previews[0]).toMatchObject({ taskUpdatedAt: now, selectedMaterialIds: [55], forcedIncludedMaterialIds: [] })
    expect(seen.creates, '取消后不得提交生成').toHaveLength(0)
  })

  test('生成确认页：确认后携带预览令牌，且请求体与预览完全一致', async ({ page }) => {
    const seen: Seen = { previews: [], creates: [] }
    let previewTokenHeader: string | undefined
    await mockSession(page)
    await page.route('**/api/ai/tasks/99', route => route.fulfill({ json: response(selectionTask) }))
    await page.route('**/api/ai/tasks/99/confirm-materials/preview', async (route: Route) => {
      seen.previews.push(route.request().postDataJSON() as Record<string, unknown>)
      await route.fulfill({ json: response(previewResponse(false)) })
    })
    await page.route('**/api/ai/tasks/99/confirm-materials', async (route: Route) => {
      seen.creates.push(route.request().postDataJSON() as Record<string, unknown>)
      previewTokenHeader = route.request().headers()['x-ai-preview-token']
      await route.fulfill({ json: response({ id: 100, taskType: 'JOB_GENERATION', status: 'PENDING' }) })
    })
    await page.route('**/api/ai/tasks/100', route => route.fulfill({
      json: response({ id: 100, taskType: 'JOB_GENERATION', status: 'RUNNING', resultJson: null }),
    }))

    await page.goto('/generate/materials?taskId=99')
    await page.getByRole('button', { name: /Confirm selection & generate resume/i }).click()
    await expect(page.getByTestId('ai-preview-overlay')).toBeVisible()
    await page.getByTestId('ai-preview-confirm').click()

    await expect.poll(() => seen.creates.length).toBe(1)
    expect(previewTokenHeader).toBe('token-redacted')
    // 预览与创建共用同一份请求体：字段一旦漂移，后端重算出的令牌就对不上（40901）
    expect(seen.creates[0]).toEqual(seen.previews[0])
  })

  test('沟通文案：取消预览则 ai-generate 一条都不发', async ({ page }) => {
    const seen: Seen = { previews: [], creates: [] }
    await mockSession(page)
    await page.route('**/api/communications/templates**', route => route.fulfill({ json: response([]) }))
    await page.route('**/api/communications/ai-generate/preview', async (route: Route) => {
      seen.previews.push(route.request().postDataJSON() as Record<string, unknown>)
      await route.fulfill({ json: response(previewResponse(false)) })
    })
    await page.route('**/api/communications/ai-generate', async (route: Route) => {
      seen.creates.push(route.request().postDataJSON() as Record<string, unknown>)
      await route.fulfill({ json: response({ id: 81, taskType: 'COMMUNICATION_GENERATE', status: 'PENDING' }) })
    })

    await page.goto('/communications')
    await page.locator('select').nth(1).selectOption('11')
    await page.locator('select').nth(2).selectOption('20')
    await page.locator('select').nth(3).selectOption('EMAIL')
    // 表单内的主按钮；同名还有顶部「AI 生成 / 模板」模式切换 tab，故按结构定位
    await page.locator('form.compact-form .generation-actions button').first().click()

    const dialog = page.getByTestId('ai-preview-overlay')
    await expect(dialog).toBeVisible()
    await page.getByTestId('ai-preview-cancel').click()
    await expect(dialog).toBeHidden()

    expect(seen.previews).toHaveLength(1)
    // 输出语言跟随界面语言（本用例为 en-US），原样透传给预览
    expect(seen.previews[0]).toEqual({ resumeVersionId: 11, jobDescriptionId: 20, type: 'EMAIL', outputLanguage: 'EN' })
    expect(seen.creates, '取消后不得创建文案任务').toHaveLength(0)
  })

  test('追问练习：取消预览则 follow-up 一条都不发', async ({ page }) => {
    const seen: Seen = { previews: [], creates: [] }
    await page.addInitScript(() => sessionStorage.setItem('interview-session-id', '5'))
    await mockSession(page)
    await page.route('**/api/interviews/5', route => route.fulfill({ json: response(interviewState) }))
    await page.route('**/api/interviews/5/report', route => route.fulfill({ json: response(interviewReport) }))
    await page.route('**/api/interviews/5/follow-up/preview', async (route: Route) => {
      seen.previews.push(route.request().postDataJSON() as Record<string, unknown>)
      await route.fulfill({ json: response(previewResponse(false)) })
    })
    await page.route('**/api/interviews/5/follow-up', async (route: Route) => {
      seen.creates.push(route.request().postDataJSON() as Record<string, unknown>)
      await route.fulfill({ json: response({ id: 61, taskType: 'INTERVIEW_COACH', status: 'PENDING' }) })
    })

    await page.goto('/interviews')
    await expect(page.getByRole('heading', { name: 'Interview report' })).toBeVisible({ timeout: 10_000 })
    await page.getByRole('button', { name: 'Generate practice' }).click()

    const dialog = page.getByTestId('ai-preview-overlay')
    await expect(dialog).toBeVisible()
    await page.getByTestId('ai-preview-cancel').click()
    await expect(dialog).toBeHidden()

    expect(seen.previews).toHaveLength(1)
    expect(seen.previews[0]).toEqual({ weakness: 'Evidence specificity' })
    expect(seen.creates, '取消后不得创建追问任务').toHaveLength(0)
  })
})
