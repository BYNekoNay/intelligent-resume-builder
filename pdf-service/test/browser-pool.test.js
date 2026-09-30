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

/** 可手动放行的渲染回调，用于制造「in-flight」窗口。 */
function gate() {
  let release
  const promise = new Promise(resolve => { release = resolve })
  return { promise, release }
}

test('caps concurrent pages and queues the excess in FIFO order', async () => {
  const browser = new FakeBrowser()
  const pool = createBrowserPool(async () => browser, { maxConcurrentPages: 1, maxQueueSize: 2 })
  const first = gate()
  const second = gate()
  const order = []

  const running = pool.withPage(async () => {
    order.push('first')
    await first.promise
  })
  const queued = pool.withPage(async () => {
    order.push('second')
    await second.promise
  })

  // 名额同步占用/入队，但渲染回调在微任务里才开始
  await new Promise(resolve => setImmediate(resolve))
  assert.deepEqual(pool.stats(), { activePages: 1, queued: 1, maxConcurrentPages: 1, maxQueueSize: 2, draining: false })
  assert.deepEqual(order, ['first'], '等待队列中的请求不得提前打开页面')

  first.release()
  await running
  await new Promise(resolve => setImmediate(resolve))
  assert.deepEqual(order, ['first', 'second'], '队首请求应在名额释放后被唤醒')
  assert.equal(browser.pages.length, 2)

  second.release()
  await queued
  assert.equal(pool.stats().activePages, 0)
  await pool.close()
})

test('rejects with a retryable 503 once the wait queue is full', async () => {
  const browser = new FakeBrowser()
  const pool = createBrowserPool(async () => browser, { maxConcurrentPages: 1, maxQueueSize: 0 })
  const running = gate()

  const first = pool.withPage(async () => { await running.promise })
  await assert.rejects(
    pool.withPage(async () => undefined),
    error => error.status === 503 && error.retryable === true && /容量已满/.test(error.message),
  )
  running.release()
  await first
  await pool.close()
})

test('drain rejects new work, lets in-flight renders finish, then reports idle', async () => {
  const browser = new FakeBrowser()
  const pool = createBrowserPool(async () => browser, { maxConcurrentPages: 1, maxQueueSize: 2 })
  const inFlight = gate()
  const running = pool.withPage(async () => { await inFlight.promise })
  const queued = pool.withPage(async () => undefined)
  queued.catch(() => {})
  assert.equal(pool.stats().queued, 1, '第二个请求应进入等待队列')

  pool.beginDrain()
  assert.equal(pool.stats().draining, true)
  assert.equal(pool.stats().queued, 0, 'drain 应清空等待队列')

  await assert.rejects(
    pool.withPage(async () => undefined),
    error => error.status === 503 && /正在关闭/.test(error.message),
  )
  await assert.rejects(queued, error => error.status === 503)

  let idle = false
  const idlePromise = pool.waitForIdle().then(() => { idle = true })
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(idle, false, 'in-flight 未结束前不得报告 idle')

  inFlight.release()
  await running
  await idlePromise
  assert.equal(idle, true)

  await pool.close()
  assert.equal(browser.closeCount, 1)
})
