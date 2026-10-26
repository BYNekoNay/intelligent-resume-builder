import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { DEFAULT_PORT, findJarIn, resolveJar, startBackend, stopBackend, waitForHealth } from '../src/backend.js'

/**
 * 桌面壳的编排冒烟测试（纯 Node，不需要 Electron / 图形会话）。
 *
 * 为什么要有它：Electron 窗口没法在无头环境验证，但**真正会出错的是编排**：
 * jar 起不来、端口被 `SERVER__PORT` 劫持、就绪判定等错端点、退出留孤儿进程。
 * 这个脚本把那段逻辑跑一遍，任何一条不对就非零退出。
 *
 * 运行：node desktop-shell/scripts/smoke.mjs
 * 前置：web 已构建、server 已打包（脚本会自己检查并给出命令）。
 */
const repoRoot = join(fileURLToPath(new URL('.', import.meta.url)), '..', '..')
// --packaged <dir>：验证**打包后**的路径（jar 在 <dir>/resources/server 下）。
// 为什么需要它：GUI 进程在无头会话里起不来，但"打包后能不能找到 jar 并起后端"是可以单独验证的，
// 而这恰恰是最容易错的一环（jar 路径 / 版本 / 旧包残留）。
const packagedIndex = process.argv.indexOf('--packaged')
const packagedDir = packagedIndex > 0 ? process.argv[packagedIndex + 1] : null
const dataDir = mkdtempSync(join(tmpdir(), 'resume-desktop-smoke-'))
const failures = []

function check(label, condition, detail = '') {
  if (condition) {
    console.log(`  [PASS] ${label}`)
  } else {
    console.log(`  [FAIL] ${label}${detail ? ' —— ' + detail : ''}`)
    failures.push(label)
  }
}

async function fetchStatus(url) {
  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(5_000) })
    return { status: response.status, text: await response.text() }
  } catch (error) {
    return { status: 0, text: String(error) }
  }
}

let backend = null
try {
  const jarPath = packagedDir ? findJarIn(join(packagedDir, 'resources', 'server')) : resolveJar(repoRoot)
  console.log(`jar: ${jarPath}`)
  console.log(`dataDir: ${dataDir}`)

  // 刻意让环境里带上 SERVER__PORT：验证编排层真的把它清掉了（否则会去抢别的端口）
  process.env.SERVER__PORT = '65530'
  const startedAt = Date.now()
  backend = startBackend({ jarPath, dataDir, port: DEFAULT_PORT })
  const health = await waitForHealth({ port: DEFAULT_PORT, timeoutMs: 120_000 })
  console.log(`  就绪耗时 ${Math.round((Date.now() - startedAt) / 1000)}s`)

  check('后端就绪（/actuator/health = UP）', health.status === 'UP', JSON.stringify(health))

  const index = await fetchStatus(`http://127.0.0.1:${DEFAULT_PORT}/`)
  check('SPA 首页可访问（200）', index.status === 200, `status=${index.status}`)
  check('首页是真的 SPA 壳（含 #app 挂载点）', index.text.includes('id="app"'))

  // 桌面端点活着 = desktop profile + 本地免登录用户注入都成立
  const settings = await fetchStatus(`http://127.0.0.1:${DEFAULT_PORT}/api/desktop/ai-settings`)
  check('本地 AI 设置端点可用（200，说明 desktop profile 生效）', settings.status === 200,
    `status=${settings.status} body=${settings.text.slice(0, 160)}`)

  // 端口没被 SERVER__PORT 劫持：65530 上不该有任何东西
  const hijacked = await fetchStatus('http://127.0.0.1:65530/actuator/health')
  check('未被 SERVER__PORT 劫持（65530 无服务）', hijacked.status === 0, `status=${hijacked.status}`)
} catch (error) {
  console.log(`  [FAIL] 冒烟执行异常：${error.message}`)
  failures.push('smoke')
} finally {
  await stopBackend(backend)
  // 停完之后端口应立刻释放（孤儿进程检测）
  await new Promise(resolve => setTimeout(resolve, 1_500))
  const afterStop = await fetchStatus(`http://127.0.0.1:${DEFAULT_PORT}/actuator/health`)
  check('退出后端口已释放（无孤儿进程）', afterStop.status === 0, `status=${afterStop.status}`)
  rmSync(dataDir, { recursive: true, force: true })
}

if (failures.length) {
  console.error(`\n冒烟失败 ${failures.length} 项：${failures.join('、')}`)
  process.exit(1)
}
console.log('\n冒烟全部通过')
