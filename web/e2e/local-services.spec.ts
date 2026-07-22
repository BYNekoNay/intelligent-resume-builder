import { expect, test } from '@playwright/test'

test.describe('@local-services local application smoke', () => {
  test.skip(process.env.LOCAL_E2E !== 'true', 'Runs only against explicitly started local services')

  test('loads the local login route without recording browser diagnostics', async ({ page }) => {
    await page.goto('/login')
    await expect(page).toHaveURL(/\/login$/)
    await expect(page.getByRole('heading')).toBeVisible()
  })

  test('creates a synthetic account through the visible registration flow and cleans it up', async ({ page }) => {
    const suffix = `${Date.now()}${Math.floor(Math.random() * 10000)}`
    let accessToken: string | undefined
    let registrationUrl: string | undefined
    page.on('request', request => {
      if (request.url().includes('/api/auth/register')) registrationUrl = request.url()
    })
    page.on('response', async response => {
      if (response.url().endsWith('/api/auth/register') && response.status() === 201) {
        accessToken = ((await response.json()) as { data: { accessToken: string } }).data.accessToken
      }
    })

    await page.goto('/register')
    const inputs = page.locator('input')
    await inputs.nth(0).fill(`local${suffix}`)
    await inputs.nth(1).fill(`local-${suffix}@example.invalid`)
    await inputs.nth(2).fill(`LocalRun-${suffix}!`)
    await page.locator('form button[type="submit"]').click()
    await expect.poll(() => registrationUrl).toBe('http://127.0.0.1:8080/api/auth/register')
    await expect(page).toHaveURL(/\/career-materials$/)
    await expect.poll(() => accessToken).toBeTruthy()

    const cleanup = await page.request.delete('http://127.0.0.1:8080/api/auth/me', {
      headers: { Authorization: `Bearer ${accessToken}` },
    })
    expect(cleanup.status()).toBe(200)
  })
})
