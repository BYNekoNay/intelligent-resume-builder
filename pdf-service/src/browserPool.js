import puppeteer from 'puppeteer'

const defaultLaunch = () => puppeteer.launch({
  headless: true,
  args: ['--no-sandbox', '--disable-setuid-sandbox'],
})

const DEFAULT_MAX_CONCURRENT_PAGES = 4
const DEFAULT_MAX_QUEUE_SIZE = 16

/**
 * 容量拒绝错误：status=503 供 HTTP 层映射为「可重试」响应（ideation「PDF readiness 与容量」）。
 */
function capacityError(message) {
  const error = new Error(message)
  error.status = 503
  error.retryable = true
  return error
}

/**
 * Reuses one Chromium process while keeping a page isolated per render.
 * A failed launch or a disconnected browser clears the cached promise so the
 * next request can recover without restarting the Node process.
 *
 * <p>容量与关闭语义（ideation「PDF readiness 与容量」，此前无许可/队列/上限）：
 * <ul>
 *   <li>最多 {@code maxConcurrentPages} 个页面同时在渲染，超出的请求进入 FIFO 等待队列；</li>
 *   <li>队列长度上限 {@code maxQueueSize}，队满或 drain 中立即返回可重试的 503，渲染请求不会无限堆积；</li>
 *   <li>{@code beginDrain()} 后拒绝新请求并清空等待队列，in-flight 渲染继续跑完，
 *       {@code waitForIdle()} 供关闭流程等待它们结束。</li>
 * </ul>
 */
export function createBrowserPool(launch = defaultLaunch, options = {}) {
  const maxConcurrentPages = options.maxConcurrentPages ?? DEFAULT_MAX_CONCURRENT_PAGES
  const maxQueueSize = options.maxQueueSize ?? DEFAULT_MAX_QUEUE_SIZE

  let browserPromise = null
  let activePages = 0
  let draining = false
  const waiters = []
  const idleWaiters = []

  function getBrowser() {
    if (browserPromise) return browserPromise

    const pending = Promise.resolve()
      .then(() => launch())
      .then(browser => {
        browser.once?.('disconnected', () => {
          if (browserPromise === pending) browserPromise = null
        })
        return browser
      })
      .catch(error => {
        if (browserPromise === pending) browserPromise = null
        throw error
      })

    browserPromise = pending
    return pending
  }

  function acquireSlot() {
    if (draining) {
      return Promise.reject(capacityError('PDF 服务正在关闭，请稍后重试'))
    }
    if (activePages < maxConcurrentPages) {
      activePages += 1
      return Promise.resolve()
    }
    if (waiters.length >= maxQueueSize) {
      return Promise.reject(capacityError('PDF 渲染容量已满，请稍后重试'))
    }
    return new Promise((resolve, reject) => waiters.push({ resolve, reject }))
  }

  function releaseSlot() {
    activePages -= 1
    const next = waiters.shift()
    if (next) {
      // 释放的名额直接转交给队首等待者（active 数不变）
      activePages += 1
      next.resolve()
      return
    }
    if (activePages === 0) {
      while (idleWaiters.length) idleWaiters.shift()()
    }
  }

  async function withPage(callback) {
    await acquireSlot()
    let page
    try {
      const browser = await getBrowser()
      page = await browser.newPage()
      return await callback(page)
    } finally {
      if (page) await page.close()
      releaseSlot()
    }
  }

  async function checkReadiness() {
    try {
      const browser = await getBrowser()
      return browser?.isConnected?.() !== false
    } catch {
      return false
    }
  }

  function stats() {
    return { activePages, queued: waiters.length, maxConcurrentPages, maxQueueSize, draining }
  }

  /** 开始 drain：拒绝新请求、清空等待队列（in-flight 渲染继续跑完）。 */
  function beginDrain() {
    draining = true
    while (waiters.length) {
      waiters.shift().reject(capacityError('PDF 服务正在关闭，请稍后重试'))
    }
  }

  /** 等待所有 in-flight 渲染结束；超时控制由调用方（关闭流程）负责。 */
  function waitForIdle() {
    if (activePages === 0) return Promise.resolve()
    return new Promise(resolve => idleWaiters.push(resolve))
  }

  async function close() {
    const pending = browserPromise
    browserPromise = null
    if (!pending) return

    const browser = await pending.catch(() => null)
    if (browser) await browser.close()
  }

  return { withPage, checkReadiness, stats, beginDrain, waitForIdle, close }
}