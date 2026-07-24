import express from 'express'
import puppeteer from 'puppeteer'
import { renderResumeHtml, TEMPLATE_CODES } from './templates/classic.js'

const app = express()
const cliPort = process.argv.find((argument) => argument.startsWith('--port='))?.slice('--port='.length)
const configuredPort = process.env.PDF_SERVICE_PORT ?? cliPort ?? '3001'
const port = Number(configuredPort)
const expectedServiceToken = process.env.PDF_SERVICE_TOKEN ?? 'dev-pdf-token-change-me'

if (!Number.isInteger(port) || port < 1 || port > 65535) {
  console.error('PDF_SERVICE_PORT must be an integer between 1 and 65535')
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

app.get('/health', (_request, response) => {
  response.json({ service: 'intelligent-resume-pdf-service', status: 'UP', stage: 'SCAFFOLD' })
})

function assertSafePayload(payload) {
  const serialized = JSON.stringify(payload)
  if (Buffer.byteLength(serialized, 'utf8') > 1024 * 1024) {
    const error = new Error('导出数据超出最大允许大小')
    error.status = 413
    throw error
  }

}

app.post('/render', requireServiceToken, async (request, response) => {
  const { templateCode, payload } = request.body ?? {}
  if (!TEMPLATE_CODES.has(templateCode)) {
    return response.status(400).json({ code: 40001, message: '不支持的简历模板' })
  }
  try {
    assertSafePayload(payload)
    const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox', '--disable-setuid-sandbox'] })
    try {
      const page = await browser.newPage()
      page.setDefaultTimeout(15_000)
      await page.setContent(renderResumeHtml(templateCode, payload), { waitUntil: 'load' })
      const pdf = await page.pdf({ format: 'A4', printBackground: true, margin: { top: '0', right: '0', bottom: '0', left: '0' } })
      response.type('application/pdf').send(Buffer.from(pdf))
    } finally {
      await browser.close()
    }
  } catch (error) {
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

app.listen(port, () => {
  console.info(`PDF service listening on http://localhost:${port}`)
})
