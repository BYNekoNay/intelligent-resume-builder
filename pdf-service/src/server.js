import express from 'express'
import { renderResumeHtml, TEMPLATE_CODES } from './templates/classic.js'
import { createBrowserPool } from './browserPool.js'

const app = express()
const cliPort = process.argv.find((argument) => argument.startsWith('--port='))?.slice('--port='.length)
const configuredPort = process.env.PDF_SERVICE_PORT ?? cliPort ?? '3001'
const port = Number(configuredPort)
// 监听地址。缺省 = 不指定 host，Node 绑定所有接口（`::`，含 IPv4）。
// 容器路径**必须**保持缺省：API 容器要经私有网络访问 `pdf-service:3001`。
// 直连部署（宿主机 systemd + 本机 nginx）必须显式收敛到回环，否则 3001 对同网段可达、
// 公网可达与否只取决于云安全组——与 api 单元的 `SERVER_ADDRESS=127.0.0.1` 同一口径
// （实测：未指定 host 时 `ss -lntp`/`Get-NetTCPConnection` 显示 `::`，且非回环地址可连通）。
const configuredHost = process.env.PDF_SERVICE_HOST ?? ''
const host = configuredHost.trim()
const expectedServiceToken = process.env.PDF_SERVICE_TOKEN ?? 'dev-pdf-token-change-me'
const production = process.env.NODE_ENV === 'production'

function positiveInteger(envName, fallback, minimum) {
  const raw = process.env[envName]
  if (raw === undefined || raw === '') return fallback
  const value = Number(raw)
  if (!Number.isInteger(value) || value < minimum) {
    console.error(`${envName} must be an integer >= ${minimum}`)
    process.exit(1)
  }
  return value
}

const maxConcurrentPages = positiveInteger('PDF_SERVICE_MAX_CONCURRENT_PAGES', 4, 1)
const maxQueueSize = positiveInteger('PDF_SERVICE_MAX_QUEUE_SIZE', 16, 0)
const queueTimeoutMs = positiveInteger('PDF_SERVICE_QUEUE_TIMEOUT_MS', 15_000, 1)
// 单次页面操作（setContent / pdf）的预算；服务端总耗时上界 = queueTimeout + 2 × renderTimeout。
// API 侧读超时（app.pdf.render-timeout-seconds，默认 50s）必须晚于该上界——内层死线先触发，
// 客户端才不会「先断开、后成功」（浪费渲染并误判失败）。该跨运行时关系由
// server 侧静态门禁 PdfDeadlineContractTest 守护。
const renderTimeoutMs = positiveInteger('PDF_SERVICE_RENDER_TIMEOUT_MS', 15_000, 1)
const drainTimeoutMs = positiveInteger('PDF_SERVICE_DRAIN_TIMEOUT_MS', 10_000, 1)
const browserPool = createBrowserPool(undefined, { maxConcurrentPages, maxQueueSize, queueTimeoutMs })

if (!Number.isInteger(port) || port < 1 || port > 65535) {
  console.error('PDF_SERVICE_PORT must be an integer between 1 and 65535')
  process.exit(1)
}

// 含空白的监听地址是被截断/拼接坏掉的配置（如把端口写进来），绑定结果不可预期 → 启动即失败。
// 与其它数值配置同一口径：非法取值 fail-closed，不留「看起来启动了但绑错接口」的中间态。
if (/\s/.test(configuredHost)) {
  console.error('PDF_SERVICE_HOST must not contain whitespace')
  process.exit(1)
}

app.disable('x-powered-by')
app.use(express.json({ limit: '1mb' }))

// 服务令牌鉴权中间件(只保护 /render;健康检查与模板下载可不带令牌)
function requireServiceToken(req, res, next) {
  const token = req.header('X-Service-Token') ?? req.header('Authorization')?.replace(/^Bearer\s+/i, '')
  if (token !== expectedServiceToken) {
    return res.status(401).json({ code: 40101, message: 'PDF 服务令牌无效' })
  }
  next()
}

app.get('/health', async (_request, response) => {
  const rendererReady = await browserPool.checkReadiness()
  const capacity = browserPool.stats()
  response.json({
    service: 'intelligent-resume-pdf-service',
    status: rendererReady && !capacity.draining ? 'UP' : 'DEGRADED',
    version: '0.1.0',
    capabilities: ['pdf-render', `${TEMPLATE_CODES.size}-resume-templates`, 'ordered-resume-sections'],
    checks: [
      { capability: 'pdf-renderer', status: rendererReady ? 'UP' : 'DOWN' },
      // 容量只上报不参与整体 status：饱和是瞬态，避免健康探针抖动（按 queued/maxQueueSize 告警）
      { capability: 'render-capacity', status: capacity.activePages >= capacity.maxConcurrentPages && capacity.queued >= capacity.maxQueueSize ? 'SATURATED' : 'UP' },
    ],
    capacity,
  })
})

function assertSafePayload(payload) {
  const serialized = JSON.stringify(payload)
  if (Buffer.byteLength(serialized, 'utf8') > 1024 * 1024) {
    const error = new Error('导出数据超出最大允许大小')
    error.status = 413
    throw error
  }

}

if (production && (expectedServiceToken.length < 32 || expectedServiceToken.toLowerCase().includes('change-me') || expectedServiceToken.toLowerCase().includes('replace-with'))) {
  console.error('PDF_SERVICE_TOKEN must be a non-default secret with at least 32 characters in production')
  process.exit(1)
}

app.post('/render', requireServiceToken, async (request, response) => {
  const { templateCode, payload } = request.body ?? {}
  if (!TEMPLATE_CODES.has(templateCode)) {
    return response.status(400).json({ code: 40001, message: '不支持的简历模板' })
  }
  try {
    assertSafePayload(payload)
    const pdf = await browserPool.withPage(async page => {
      page.setDefaultTimeout(renderTimeoutMs)
      await page.setContent(renderResumeHtml(templateCode, payload), { waitUntil: 'load' })
      return page.pdf({ format: 'A4', printBackground: true, margin: { top: '0', right: '0', bottom: '0', left: '0' } })
    })
    response.type('application/pdf').send(Buffer.from(pdf))
  } catch (error) {
    // 容量/drain 拒绝：显式可重试语义（503 + Retry-After），与 API 侧「失败可重试」一致
    if (error?.status === 503) {
      response.set('Retry-After', '2')
      return response.status(503).json({ code: 50301, message: error.message, retryable: true })
    }
    const status = error?.status ?? 500
    response.status(status).json({ code: status === 400 || status === 413 ? 40001 : 50003, message: error instanceof Error ? error.message : 'PDF 渲染失败' })
  }
})

app.use((error, _request, response, _next) => {
  if (error instanceof SyntaxError) {
    response.status(400).json({ code: 40001, message: '请求体必须是合法 JSON' })
    return
  }
  response.status(500).json({ code: 50001, message: '系统异常' })
})

app.use((_request, response) => {
  response.status(404).json({ code: 40401, message: '资源不存在' })
})

// 启动日志打出**实际绑定地址**（缺省显式为 0.0.0.0）：排查「改了没生效」时以运行时事实为准，
// 直接对应 `ss -lntp` / `Get-NetTCPConnection -LocalPort <port>` 的第一个字段。
function onListening() {
  console.info(`PDF service listening on http://${host === '' ? '0.0.0.0' : host}:${port}`)
}

const server = host === '' ? app.listen(port, onListening) : app.listen(port, host, onListening)

let shuttingDown = false
async function shutdown(signal) {
  if (shuttingDown) return
  shuttingDown = true
  console.info(`PDF service received ${signal}, draining in-flight renders`)
  browserPool.beginDrain()
  const idle = await Promise.race([
    browserPool.waitForIdle().then(() => true),
    new Promise(resolve => {
      setTimeout(() => resolve(false), drainTimeoutMs).unref()
    }),
  ])
  if (!idle) {
    console.warn(`PDF service drain timed out after ${drainTimeoutMs}ms, closing with in-flight renders`)
  }
  try {
    await browserPool.close()
    await new Promise(resolve => server.close(resolve))
    process.exit(0)
  } catch (error) {
    console.error('PDF service shutdown failed', error)
    process.exit(1)
  }
}

process.once('SIGINT', () => { void shutdown('SIGINT') })
process.once('SIGTERM', () => { void shutdown('SIGTERM') })
