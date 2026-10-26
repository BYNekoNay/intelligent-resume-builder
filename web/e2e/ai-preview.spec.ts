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
