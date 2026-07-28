import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'

const now = '2026-07-22T10:00:00Z'
const response = (data: unknown) => ({ code: 0, message: 'ok', data, traceId: 'e2e' })
const resume = { id: 1, title: 'Backend resume', currentVersionId: 11, jobDescriptionId: null, createdAt: now, updatedAt: now }
const version = { id: 11, resumeId: 1, versionNo: 1, sourceType: 'MANUAL', resumeJson: {}, optimizationSummary: null, createdAt: now }
const job = { id: 20, title: 'Backend Engineer', companyName: 'Example Systems', jdText: 'Java and Spring Boot', parsedKeywordsJson: null, parsedAt: null, parsedVersion: null, createdAt: now, updatedAt: now }
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
  await page.route('**/api/career-materials*', route => route.fulfill({ json: response([]) }))
  await page.route('**/api/personal-profile*', route => route.fulfill({ json: response({ fullName: '', email: '', phone: '', location: '', website: '', profileSummary: '' }) }))
  await page.route('**/api/applications', route => route.fulfill({ json: response([]) }))
  await page.route('**/api/interview-answer-assets**', route => route.fulfill({ json: response([]) }))
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

  await expect(page.getByRole('button', { name: 'Career records' })).toBeVisible()
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
  await expect(draft.getByText('姓名', { exact: true })).toBeVisible()
  await expect(draft.getByText('BYNeko', { exact: true })).toBeVisible()
  await expect(draft.getByText('公司', { exact: true })).toBeVisible()
  await expect(draft).toContainText('将核心接口 P99 降至 160ms')
  await expect(draft).not.toContainText('work[0]')
  await expect(draft).not.toContainText('"company"')
  await expect(draft).not.toContainText('materialId=62')
  const qualitySummary = draft.locator('.quality-summary')
  await expect(qualitySummary.getByRole('heading', { name: '草稿质量摘要' })).toBeVisible()
  await expect(qualitySummary).toContainText('需要处理')
  await expect(qualitySummary).toContainText('待核实内容')

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
  const versionSelect = page.locator('select').nth(1)
  await expect(versionSelect).toBeEnabled()
  await expect(versionSelect.locator('option[value="11"]')).toHaveCount(1)
  await versionSelect.selectOption('11')
  await page.locator('select').nth(2).selectOption('20')
  await page.locator('select').nth(3).selectOption('EMAIL')
  const communicationForm = page.locator('form.compact-form')
  await communicationForm.locator('button').click()
  await expect.poll(() => generationPayload).toEqual({ resumeVersionId: 11, jobDescriptionId: 20, type: 'EMAIL' })
  const editor = page.locator('article.workspace-card textarea')
  await editor.fill('Edited email body')
  await page.locator('article.workspace-card .job-actions button').nth(1).click()
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
  await historicalCard.locator('button').nth(2).click()
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
