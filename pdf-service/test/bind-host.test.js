import { spawn, spawnSync } from 'node:child_process'
import assert from 'node:assert/strict'
import { test } from 'node:test'

// 第四十批取证的回归门禁：PDF 服务的监听地址此前**不可配置**（`app.listen(port)` 未指定 host），
// 直连部署路径下实际绑定所有接口（`::`），3001 对同网段可达、公网可达与否只取决于云安全组，
// 违反 08 §3.3/§10.1 的「PDF 服务只监听私有网络接口 / 不得暴露公网」。
// 现由 `PDF_SERVICE_HOST` 控制（缺省仍为所有接口，容器路径依赖该缺省）。

test('startup rejects a bind address containing whitespace', () => {
  const result = spawnSync(process.execPath, ['src/server.js', '--port=3198'], {
    cwd: process.cwd(),
    env: { ...process.env, PDF_SERVICE_HOST: '127.0.0.1 3001' },
    encoding: 'utf8',
  })

  assert.equal(result.status, 1)
  assert.match(result.stderr, /PDF_SERVICE_HOST must not contain whitespace/)
})

test('PDF_SERVICE_HOST is honored as the announced and effective bind address', async () => {
  const port = 3199
  const child = spawn(process.execPath, ['src/server.js'], {
    cwd: process.cwd(),
    env: { ...process.env, PDF_SERVICE_PORT: String(port), PDF_SERVICE_HOST: '127.0.0.1' },
    stdio: ['ignore', 'pipe', 'pipe'],
  })

  try {
    // 启动日志必须打出**实际绑定地址**（而不是固定文案 localhost）：运维以运行时事实核对
    // 「改了没生效」，这是本门禁的观测面。
    const announced = await firstLineMatching(child, /PDF service listening on/)
    assert.match(
      announced,
      new RegExp(`http://127\\.0\\.0\\.1:${port}$`),
      `启动日志应报出真实绑定地址，实际为：${announced}`,
    )

    // 回环上确实在服务（无令牌 → 401，避免触发 Chromium 渲染）
    const response = await fetch(`http://127.0.0.1:${port}/render`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
      signal: AbortSignal.timeout(5_000),
    })
    assert.equal(response.status, 401)
  } finally {
    child.kill()
  }
})

function firstLineMatching(child, pattern) {
  return new Promise((resolve, reject) => {
    let buffer = ''
    const timer = setTimeout(() => {
      reject(new Error(`等待启动日志超时，已收到：${buffer}`))
    }, 10_000)
    const finish = (line) => {
      clearTimeout(timer)
      resolve(line)
    }
    child.stdout.on('data', (chunk) => {
      buffer += chunk.toString()
      const matched = buffer
        .split(/\r?\n/)
        .find((line) => pattern.test(line))
      if (matched !== undefined) finish(matched)
    })
    child.once('exit', (code) => {
      clearTimeout(timer)
      reject(new Error(`PDF 服务提前退出，code=${code}，已收到：${buffer}`))
    })
  })
}
