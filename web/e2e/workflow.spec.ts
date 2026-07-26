import { expect, test, type Page } from '@playwright/test'

const now = '2026-07-22T10:00:00Z'
const response = (data: unknown) => ({ code: 0, message: 'ok', data, traceId: 'e2e' })
const resume = { id: 1, title: 'Backend resume', currentVersionId: 11, jobDescriptionId: null, createdAt: now, updatedAt: now }
const version = { id: 11, resumeId: 1, versionNo: 1, sourceType: 'MANUAL', resumeJson: {}, optimizationSummary: null, createdAt: now }
const job = { id: 20, title: 'Backend Engineer', companyName: 'Example Systems', jdText: 'Java and Spring Boot', parsedKeywordsJson: null, parsedAt: null, parsedVersion: null, createdAt: now, updatedAt: now }

async function mockAuthenticatedApi(page: Page) {
  await page.route('**/api/auth/refresh', route => route.fulfill({ json: response({ accessToken: 'e2e-token' }) }))
  await page.route('**/api/auth/me', route => route.fulfill({ json: response({ id: 99, username: 'e2e-user', email: 'e2e@example.com' }) }))
  await page.route('**/api/resumes', route => route.fulfill({ json: response([resume]) }))
  await page.route('**/api/resumes/1', route => route.fulfill({ json: response(resume) }))
  await page.route('**/api/resumes/1/versions', route => route.fulfill({ json: response([version]) }))
  await page.route('**/api/jobs', route => route.fulfill({ json: response([job]) }))
  await page.route('**/api/career-materials*', route => route.fulfill({ json: response([]) }))
  await page.route('**/api/personal-profile*', route => route.fulfill({ json: response({ fullName: '', email: '', phone: '', location: '', website: '', profileSummary: '' }) }))
  await page.route('**/api/applications', route => route.fulfill({ json: response([]) }))
}

test('takes an authenticated user from the home start action to the generation workbench', async ({ page }) => {
  await mockAuthenticatedApi(page)

  await page.goto('/')
  await page.getByRole('link', { name: '开始使用' }).click()

  await expect(page).toHaveURL(/\/generate$/)
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

  await form.getByLabel('来源原文').fill('Updated and still verifiable source text.')
  await form.getByRole('button', { name: '保存修改' }).click()
  await expect.poll(() => updatePayload).toMatchObject({
    materialType: 'HIGHLIGHT',
    sourceText: 'Updated and still verifiable source text.',
    contentJson: detail.contentJson,
  })
})

test('loads a personal profile suggestion without saving it automatically', async ({ page }) => {
  await mockAuthenticatedApi(page)
  let savedProfile: unknown
  await page.route('**/api/personal-profile/import-suggestion*', route => route.fulfill({ json: response({
    fullName: 'BYNeko', email: '2149752131@qq.com', phone: '13800000000', location: '杭州',
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
  await page.getByLabel('从已有简历提取建议').selectOption('1')
  await page.getByRole('button', { name: '读取建议' }).click()
  await expect(page.getByLabel('姓名')).toHaveValue('BYNeko')
  expect(savedProfile).toBeUndefined()
  await page.getByRole('button', { name: '保存个人档案' }).click()
  await expect.poll(() => savedProfile).toMatchObject({ fullName: 'BYNeko', email: '2149752131@qq.com' })
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
    return route.fulfill({ json: response([workMaterial]) })
  })

  await page.goto('/career-materials')
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
  await page.route('**/api/resumes/1/versions', async route => {
    if (route.request().method() === 'GET') return route.fulfill({ json: response([version]) })
    saved = route.request().postDataJSON()
    return route.fulfill({ json: response(editorVersion) })
  })

  await page.goto('/resumes/1/edit')
  const bodySize = page.getByLabel('正文字号滑杆')
  await expect(bodySize).toHaveValue('13')
  await bodySize.fill('16')
  await expect(page.locator('.resume-paper')).toHaveCSS('--resume-body-size', '16px')
  const headingSize = page.getByLabel('标题字号滑杆')
  await headingSize.fill('18')
  await expect(page.locator('.paper-header h2')).toHaveCSS('font-size', '41.5385px')
  await page.getByLabel('字体风格').selectOption('songti')
  await expect(page.locator('.resume-paper')).toHaveCSS('--resume-font-family', '"Songti SC", SimSun, serif')
  await page.getByRole('button', { name: '保存新版本' }).click()
  await expect.poll(() => saved).toMatchObject({ resumeJson: { layout: { bodyFontSize: 16, headingFontSize: 18, fontFamily: 'songti' } } })
})

test('switches the application chrome and answer library between Chinese and English', async ({ page }) => {
  await mockAuthenticatedApi(page)

  await page.goto('/interview-assets')
  await expect(page.getByRole('heading', { name: '面试答案资产' })).toBeVisible()

  await page.getByRole('button', { name: 'EN' }).click()

  await expect(page.getByRole('link', { name: 'Workspace' })).toBeVisible()
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
    policyVersion: 'v1.1.0',
    providerCode: 'bailian',
    taskScopes: ['JOB_MATERIAL_SELECTION', 'JOB_GENERATION'],
    dataCategories: ['CAREER_MATERIAL', 'JOB_DESCRIPTION', 'PERSONAL_PROFILE'],
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
        },
        selected: [],
        unselected: [],
        missing: [],
        warnings: [],
      },
    }),
  }))

  await page.goto('/generate/confirm?taskId=33')
  const draft = page.locator('.draft-container')
  await expect(draft.getByText('姓名', { exact: true })).toBeVisible()
  await expect(draft.getByText('BYNeko', { exact: true })).toBeVisible()
  await expect(draft.getByText('公司', { exact: true })).toBeVisible()
  await expect(draft).toContainText('将核心接口 P99 降至 160ms')
  await expect(draft).not.toContainText('work[0]')
  await expect(draft).not.toContainText('"company"')
  await expect(draft).not.toContainText('materialId=62')

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

  const basicsSection = page.locator('.draft-section').filter({ hasText: '个人概要' })
  await basicsSection.getByRole('button', { name: '编辑' }).click()
  await expect(page.getByRole('dialog', { name: '编辑内容' }).getByRole('textbox', { name: '邮箱' })).toBeVisible()
})

test('generates an editable communication draft and carries it into an application', async ({ page }) => {
  await mockAuthenticatedApi(page)
  let generationPayload: unknown
  await page.route('**/api/communications/generate', async route => {
    generationPayload = route.request().postDataJSON()
    await route.fulfill({ json: response({ type: 'EMAIL', draft: 'Hello Example Systems, I would like to apply.', sentAutomatically: false, requiresManualConfirmation: true }) })
  })
  await page.goto('/communications')
  await page.locator('select').nth(1).selectOption('11')
  await page.locator('select').nth(2).selectOption('20')
  await page.locator('select').nth(3).selectOption('EMAIL')
  await page.getByRole('button', { name: 'Generate draft' }).click()
  await expect.poll(() => generationPayload).toEqual({ resumeVersionId: 11, jobDescriptionId: 20, type: 'EMAIL' })
  const editor = page.getByLabel('Editable draft')
  await editor.fill('Edited email body')
  await page.getByRole('button', { name: 'Use in application' }).click()
  await expect(page).toHaveURL(/\/applications$/)
  await expect(page.locator('textarea').nth(1)).toHaveValue('Edited email body')
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
  await expect(page.getByText(/PENDING|RUNNING/)).toBeVisible()
  await expect(page.getByText(/SUCCESS/)).toBeVisible()
  const download = page.waitForEvent('download')
  await actions.first().click()
  expect((await download).suggestedFilename()).toBe('resume.pdf')
})
