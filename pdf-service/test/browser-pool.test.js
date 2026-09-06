import assert from 'node:assert/strict'
import test from 'node:test'
import { createBrowserPool } from '../src/browserPool.js'

class FakePage {
  closed = false

  async close() {
    this.closed = true
  }
}

class FakeBrowser {
  pages = []
  closeCount = 0
  listeners = new Map()

  once(event, listener) {
    this.listeners.set(event, listener)
  }

  async newPage() {
    const page = new FakePage()
    this.pages.push(page)
    return page
  }

  async close() {
    this.closeCount += 1
  }

  disconnect() {
    this.listeners.get('disconnected')?.()
  }
}

test('shares one browser across concurrent pages and closes every page', async () => {
  const browser = new FakeBrowser()
  let launches = 0
  const pool = createBrowserPool(async () => {
    launches += 1
    return browser
  })

  await Promise.all([
    pool.withPage(async page => page),
    pool.withPage(async page => page),
  ])

  assert.equal(launches, 1)
  assert.equal(browser.pages.length, 2)
  assert.ok(browser.pages.every(page => page.closed))

  await pool.close()
  assert.equal(browser.closeCount, 1)
})

test('recreates the browser after a disconnect', async () => {
  const browsers = []
  const pool = createBrowserPool(async () => {
    const browser = new FakeBrowser()
    browsers.push(browser)
    return browser
  })

  await pool.withPage(async () => undefined)
  browsers[0].disconnect()
  await pool.withPage(async () => undefined)

  assert.equal(browsers.length, 2)
  await pool.close()
})

test('clears a failed launch so the next request can retry', async () => {
  let launches = 0
  const browser = new FakeBrowser()
  const pool = createBrowserPool(async () => {
    launches += 1
    if (launches === 1) throw new Error('launch failed')
    return browser
  })

  await assert.rejects(pool.withPage(async () => undefined), /launch failed/)
  await pool.withPage(async () => undefined)

  assert.equal(launches, 2)
  await pool.close()
})

test('reports renderer readiness without launching a second browser', async () => {
  const browser = new FakeBrowser()
  let launches = 0
  const pool = createBrowserPool(async () => {
    launches += 1
    return browser
  })

  assert.equal(await pool.checkReadiness(), true)
  assert.equal(await pool.checkReadiness(), true)
  assert.equal(launches, 1)

  browser.disconnect()
  assert.equal(await pool.checkReadiness(), true)
  await pool.close()
})

test('reports renderer as not ready when Chromium launch fails', async () => {
  const pool = createBrowserPool(async () => {
    throw new Error('launch failed')
  })

  assert.equal(await pool.checkReadiness(), false)
  await pool.close()
})
