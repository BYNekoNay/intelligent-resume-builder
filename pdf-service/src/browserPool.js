import puppeteer from 'puppeteer'

const defaultLaunch = () => puppeteer.launch({
  headless: true,
  args: ['--no-sandbox', '--disable-setuid-sandbox'],
})

/**
 * Reuses one Chromium process while keeping a page isolated per render.
 * A failed launch or a disconnected browser clears the cached promise so the
 * next request can recover without restarting the Node process.
 */
export function createBrowserPool(launch = defaultLaunch) {
  let browserPromise = null

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

  async function withPage(callback) {
    const browser = await getBrowser()
    const page = await browser.newPage()
    try {
      return await callback(page)
    } finally {
      await page.close()
    }
  }

  async function close() {
    const pending = browserPromise
    browserPromise = null
    if (!pending) return

    const browser = await pending.catch(() => null)
    if (browser) await browser.close()
  }

  return { withPage, close }
}
