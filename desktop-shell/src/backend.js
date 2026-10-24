import { spawn } from 'node:child_process'
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'

/**
 * 后端进程编排（ADR-012 阶段六的**可测试核心**）。
 *
 * 刻意把「起 jar / 等就绪 / 停」从 Electron 主进程里拆出来：
 * Electron 需要图形会话，无法在 CI 或无头环境验证；而这段逻辑才是真正会出错的地方
 * （端口被劫持、就绪判定错误、退出时留下孤儿进程）。拆开后可以用纯 Node 跑冒烟测试。
 */

/** 桌面模式固定端口（application-desktop.yml 的默认值）；端口被占时会失败而不是悄悄换端口。 */
export const DEFAULT_PORT = 8082

/**
 * 找出要启动的 jar。
 *
 * 不接受任意路径：shell 只认 `server/target` 下带版本号的那个产物，
 * 避免误把旧 jar 或半成品启动起来（"跑起来了但不是这次构建的产物"最难查）。
 */
export function resolveJar(repoRoot) {
  const targetDir = join(repoRoot, 'server', 'target')
  const candidates = [
    join(targetDir, `intelligent-resume-server-${readVersion(repoRoot)}.jar`),
  ]
  for (const candidate of candidates) {
    if (existsSync(candidate)) return candidate
  }
  throw new Error(
    `找不到后端 jar。先构建：cd web && npm run build && cd ../server && mvn -DskipTests package\n` +
      `期望位置：${candidates.join(' 或 ')}`,
  )
}

/** 从 pom.xml 读版本，避免在 shell 里再硬编码一份（版本号副本已经有 11 处）。 */
export function readVersion(repoRoot) {
  const pom = join(repoRoot, 'server', 'pom.xml')
  if (!existsSync(pom)) throw new Error(`找不到 ${pom}`)
  const text = readFileSync(pom, 'utf8')
  // 只取 project 自己的 <version>（parent 的 version 在前面，所以用 artifactId 之后的那一段）
  const match = /<artifactId>intelligent-resume-server<\/artifactId>\s*<version>([^<]+)<\/version>/m.exec(text)
    ?? /<version>([^<]+)<\/version>\s*<\/project>/m.exec(text)
  if (!match) throw new Error('无法从 pom.xml 解析版本号')
  return match[1].trim()
}

/**
 * 启动后端（桌面模式）。
 *
 * 三条必须做对的事，每条都对应一个真实踩过的坑：
 *  1. `--server.port` **显式传**：环境里若存在 `SERVER__PORT`（双下划线），Spring 的宽松绑定会
 *     静默覆盖 `application-desktop.yml` 里的 8082 —— 表现为"配置写 8082，实际去抢别的端口"；
 *  2. `--server.address=127.0.0.1` 必传：软件启动时会校验监听地址，非回环直接拒绝启动（ADR-012）；
 *  3. 数据目录必须**绝对路径**：H2 拒绝隐式相对路径。
 */
export function startBackend({ jarPath, dataDir, port = DEFAULT_PORT, javaCommand = 'java', onLog = () => {} }) {
  const args = [
    '-jar', jarPath,
    '--spring.profiles.active=desktop',
    `--server.address=127.0.0.1`,
    `--server.port=${port}`,
  ]
  const env = { ...process.env, DESKTOP_DATA_DIR: dataDir }
  // 清掉会劫持端口的注入变量（Spring 宽松绑定：SERVER__PORT → server.port）
  delete env.SERVER__PORT
  delete env.SERVER__HOST

  const child = spawn(javaCommand, args, { env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] })
  child.stdout.on('data', chunk => onLog(String(chunk)))
  child.stderr.on('data', chunk => onLog(String(chunk)))
  return child
}

/** 轮询 `/actuator/health` 直到 UP。刻意不用 `/api/system/health`：它会把 pdf-renderer 报成 DOWN。 */
export async function waitForHealth({ port = DEFAULT_PORT, timeoutMs = 90_000, intervalMs = 500, fetchImpl = fetch }) {
  const deadline = Date.now() + timeoutMs
  let lastError = null
  while (Date.now() < deadline) {
    try {
      const response = await fetchImpl(`http://127.0.0.1:${port}/actuator/health`, { signal: AbortSignal.timeout(2_000) })
      if (response.ok) {
        const body = await response.json()
        if (body?.status === 'UP') return body
      }
    } catch (error) {
      lastError = error
    }
    await delay(intervalMs)
  }
  throw new Error(`后端在 ${timeoutMs}ms 内未就绪（最后错误：${lastError?.message ?? '无'}）`)
}

/** 停后端：先 SIGTERM，等它自己退出；超时再强杀，避免留下孤儿 java 进程占着 8082。 */
export async function stopBackend(child, { timeoutMs = 15_000 } = {}) {
  if (!child || child.exitCode !== null) return
  const exited = new Promise(resolve => child.once('exit', resolve))
  child.kill('SIGTERM')
  const timeout = delay(timeoutMs).then(() => 'timeout')
  const result = await Promise.race([exited, timeout])
  if (result === 'timeout') {
    child.kill('SIGKILL')
    await Promise.race([exited, delay(5_000)])
  }
}
