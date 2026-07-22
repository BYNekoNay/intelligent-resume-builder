import { defineConfig, devices } from '@playwright/test'

const localServices = process.env.LOCAL_E2E === 'true'
const localBaseUrl = process.env.LOCAL_E2E_BASE_URL ?? 'http://127.0.0.1:5173'
const allowedLocalOrigins = new Set(['http://127.0.0.1:5173', 'http://localhost:5173'])

if (localServices && !allowedLocalOrigins.has(localBaseUrl)) {
  throw new Error('LOCAL_E2E_BASE_URL must be an approved loopback origin.')
}

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  reporter: 'list',
  use: {
    baseURL: localServices ? localBaseUrl : 'http://127.0.0.1:4173',
    trace: localServices ? 'off' : 'on-first-retry',
    screenshot: 'off',
    video: 'off',
  },
  webServer: localServices ? undefined : {
    command: 'npm run dev -- --host 127.0.0.1 --port 4173',
    url: 'http://127.0.0.1:4173',
    reuseExistingServer: process.env.PLAYWRIGHT_REUSE_SERVER === 'true',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
