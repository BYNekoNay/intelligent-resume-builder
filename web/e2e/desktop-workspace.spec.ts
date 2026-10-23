import { expect, test, type Page } from '@playwright/test'

// 批次 1.6：桌面工作区（备份/恢复 + 导入 Web 导出包）的前端入口。
//
// 这里守四件事：
//  1. 在线部署下**看不到入口**，直接访问 /desktop 会被守卫送回工作台（端点返回 404）；
//  2. 桌面版下列表/创建/恢复都能走通，恢复必须**二次确认**；
//  3. 恢复失败时 HTTP 409 但信封 code=0，界面读的是 `data.ok` 而不是 `code`；
//  4. 导入的冲突项会把 `resolutions` 按**子记录自己的 stableId** 提交（含嵌套集合）。

const response = (data: unknown) => ({ code: 0, message: 'ok', data, traceId: 'desktop-e2e' })

const backup = (createdAt: string, safety = false) => ({
  id: `backup-${createdAt}`,
  fileName: `backup-${createdAt}.sql`,
  sizeBytes: 40_960,
  createdAt,
  safetyBackup: safety,
})

const previewPayload = {
  packageId: 'web-export-1',
  added: 1,
  skipped: 1,
  conflicts: 1,
  unsupportedCollections: [],
  items: [
    { collection: 'jobDescriptions', stableId: 'web:jobDescriptions:7', decision: 'ADD', entityType: 'job_description', localId: null, reason: 'new', localSnapshot: null, importedSnapshot: {} },
    { collection: 'resumes', stableId: 'web:resumes:5', decision: 'SKIP', entityType: 'resume', localId: 12, reason: 'same', localSnapshot: {}, importedSnapshot: {} },
    { collection: 'resumes.versions', stableId: 'web:resumes:5:versions:9', decision: 'CONFLICT', entityType: 'resume_version', localId: 13, reason: 'changed', localSnapshot: {}, importedSnapshot: {} },
  ],
}

async function mockAuthenticatedApi(page: Page) {
  await page.route('**/api/auth/refresh', route => route.fulfill({ json: response({ accessToken: 'e2e-token' }) }))
  await page.route('**/api/auth/me', route => route.fulfill({
    json: response({ id: 99, username: 'local-desktop', email: 'e2e@example.com', displayName: 'E2E' }),
  }))
  await page.route('**/api/resumes**', route => route.fulfill({ json: response([]) }))
}

test.describe('desktop workspace', () => {
  // 统一英文界面：本文件按英文文案断言
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => localStorage.setItem('intelligent-resume.locale', 'en-US'))
  })

  test('在线部署：入口不出现，直接访问被送回工作台', async ({ page }) => {
    await mockAuthenticatedApi(page)
    // 在线版端点不存在：已登录是 404，未登录是 401 —— 这里给 404
    await page.route('**/api/desktop/**', route => route.fulfill({
      status: 404,
      json: { code: 40401, message: 'not found', data: null, traceId: 'desktop-e2e' },
    }))

    await page.goto('/desktop')

    await expect(page).toHaveURL(/\/$/)
    // 展开导航组再断言：链接在折叠面板里，不展开的话「数量为 0」是假通过
    await page.getByRole('button', { name: 'Prepare evidence' }).click()
    await expect(page.locator('a[href="/desktop"]')).toHaveCount(0)
  })

  test('桌面版：备份列表、创建、恢复（含二次确认）', async ({ page }) => {
    await mockAuthenticatedApi(page)
    const created: string[] = []
    let backups = [backup('20261023-101010'), backup('20261023-095959', true)]

    await page.route('**/api/desktop/backups', route => {
      if (route.request().method() === 'POST') {
        created.push('created')
        backups = [backup('20261023-111111'), ...backups]
        return route.fulfill({ status: 201, json: response(backup('20261023-111111')) })
      }
      return route.fulfill({ json: response(backups) })
    })
    await page.route('**/api/desktop/backups/*/restore', route => route.fulfill({
      json: response({ restoredId: 'backup-20261023-101010', safetyBackupId: 'safety-1', ok: true, message: 'restored' }),
    }))

    await page.goto('/desktop')

    // 入口可见（说明探测成功）。导航项在折叠面板里，先展开。
    await page.getByRole('button', { name: 'Prepare evidence' }).click()
    await expect(page.locator('a[href="/desktop"]')).toBeVisible()
    await page.getByRole('button', { name: 'Prepare evidence' }).click()
    // 列表渲染：安全备份会被标出来
    await expect(page.getByRole('button', { name: 'Restore this snapshot' }).first()).toBeVisible()
    await expect(page.locator('.desktop-backup-item')).toHaveCount(2)

    // 创建备份 → 列表多一条
    await page.getByRole('button', { name: 'Back up now' }).click()
    await expect(page.locator('.desktop-backup-item')).toHaveCount(3)
    expect(created).toHaveLength(1)

    // 恢复：必须先弹确认，再发请求
    await page.getByRole('button', { name: 'Restore this snapshot' }).first().click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()
    await expect(dialog.getByRole('button', { name: 'Restore' })).toBeVisible()
    await dialog.getByRole('button', { name: 'Restore' }).click()

    await expect(page.getByRole('button', { name: 'Reload workspace' })).toBeVisible()
  })

  test('桌面版：恢复失败时读 data.ok，而不是信封 code', async ({ page }) => {
    await mockAuthenticatedApi(page)
    await page.route('**/api/desktop/backups', route => route.fulfill({
      json: response([backup('20261023-101010')]),
    }))
    // 关键：HTTP 409，但 code 仍是 0、data.ok=false —— 按 code 判断会当成成功
    await page.route('**/api/desktop/backups/*/restore', route => route.fulfill({
      status: 409,
      json: { code: 0, message: 'success', traceId: 'desktop-e2e',
        data: { restoredId: 'missing', safetyBackupId: null, ok: false, message: 'snapshot missing' } },
    }))

    await page.goto('/desktop')
    await page.getByRole('button', { name: 'Restore this snapshot' }).first().click()
    await page.getByRole('dialog').getByRole('button', { name: 'Restore' }).click()

    await expect(page.getByRole('alert')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Reload workspace' })).toHaveCount(0)
  })

  test('桌面版：导入预览后按子记录 stableId 提交冲突决策', async ({ page }) => {
    await mockAuthenticatedApi(page)
    await page.route('**/api/desktop/backups', route => route.fulfill({ json: response([]) }))
    await page.route('**/api/desktop/import/preview', route => route.fulfill({ json: response(previewPayload) }))
    const applied: unknown[] = []
    await page.route('**/api/desktop/import/apply', async route => {
      applied.push(route.request().postDataJSON())
      await route.fulfill({
        json: response({ inserted: 1, updated: 1, skipped: 1, keptLocal: 0, insertedStableIds: ['web:jobDescriptions:7'] }),
      })
    })

    await page.goto('/desktop')

    await page.locator('input[type="file"]').setInputFiles({
      name: 'workspace.json',
      mimeType: 'application/json',
      buffer: Buffer.from(JSON.stringify({ formatVersion: 3, manifest: { packageId: 'web-export-1' } })),
    })
    await page.getByRole('button', { name: 'Preview classification' }).click()

    await expect(page.getByText('1 new · 1 skipped · 1 conflicts')).toBeVisible()
    // 嵌套记录的集合路径要原样显示（证明 UI 没把它压平）
    await expect(page.getByText('resumes.versions').first()).toBeVisible()

    await page.locator('.desktop-conflict-row select').selectOption('USE_IMPORTED')
    await page.getByRole('button', { name: 'Apply import' }).click()

    await expect(page.getByText(/Import finished/)).toBeVisible()
    expect(applied).toHaveLength(1)
    expect(applied[0]).toMatchObject({
      package: { formatVersion: 3 },
      resolutions: { 'web:resumes:5:versions:9': 'USE_IMPORTED' },
    })
  })

  test('桌面版：含不支持集合时禁用应用', async ({ page }) => {
    await mockAuthenticatedApi(page)
    await page.route('**/api/desktop/backups', route => route.fulfill({ json: response([]) }))
    await page.route('**/api/desktop/import/preview', route => route.fulfill({
      json: response({ ...previewPayload, unsupportedCollections: ['interviewSessions'] }),
    }))

    await page.goto('/desktop')
    await page.locator('input[type="file"]').setInputFiles({
      name: 'workspace.json',
      mimeType: 'application/json',
      buffer: Buffer.from(JSON.stringify({ formatVersion: 3, manifest: { packageId: 'p' } })),
    })
    await page.getByRole('button', { name: 'Preview classification' }).click()

    await expect(page.getByText(/does not support/)).toBeVisible()
    await expect(page.getByRole('button', { name: 'Apply import' })).toBeDisabled()
  })
})
