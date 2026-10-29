import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * 打包前把**当前版本**的 jar 暂存到 `vendor/`，供 electron-builder 的 extraResources 复制。
 *
 * <p>为什么不让 extraResources 直接指 `../server/target` 并按通配符筛：
 * 2026-10-26 实测踩到 —— target 里躺着 0.4.0 / 0.4.1 两代旧 jar，通配符会把它们**一起打进安装包**，
 * 而 `findJarIn` 取的是目录里第一个匹配项，于是装出来跑的是旧版本。
 * 这类"跑起来了但不是这次构建的产物"最难查，所以在构建入口就消灭掉。
 *
 * <p>版本号从 `server/pom.xml` 读（与后端其它 11 处副本同源），找不到就明确报错而不是猜。
 */
const repoRoot = join(fileURLToPath(new URL('.', import.meta.url)), '..', '..')
const pom = join(repoRoot, 'server', 'pom.xml')
if (!existsSync(pom)) {
  throw new Error(`找不到 ${pom}`)
}
const version = /<artifactId>intelligent-resume-server<\/artifactId>\s*<version>([^<]+)<\/version>/m
  .exec(readFileSync(pom, 'utf8'))?.[1]
if (!version) {
  throw new Error('无法从 server/pom.xml 解析版本号')
}

const targetDir = join(repoRoot, 'server', 'target')
const jarName = `intelligent-resume-server-${version}.jar`

// 壳版本必须与产品版本同轨（2026-10-29 起 VersionConsistencyContractTest 也守着这两处，
// 但那里跑在 CI/单测里；打包入口再拦一次，因为打出来的安装包无法事后改）：
// release-v042 曾把 0.4.2 的 jar 打进「0.1.0 壳」，装完从版本号根本看不出内嵌的是哪一代后端。
const shellPkg = join(repoRoot, 'desktop-shell', 'package.json')
const shellVersion = JSON.parse(readFileSync(shellPkg, 'utf8')).version
if (shellVersion !== version) {
  throw new Error(
    `桌面壳版本(${shellVersion}) 与产品版本(${version}) 不一致：请先同步 desktop-shell/package.json\n` +
      `（安装包内嵌后端 jar，壳版本脱钩会让产物失去可追溯性）`,
  )
}

const source = join(targetDir, jarName)
if (!existsSync(source)) {
  throw new Error(
    `找不到 ${jarName}。先构建：cd server && mvn -DskipTests package\n` +
      `（target 里现有：${existsSync(targetDir) ? readdirSync(targetDir).filter(f => f.endsWith('.jar')).join(', ') : '目录不存在'}）`,
  )
}

const vendorDir = join(repoRoot, 'desktop-shell', 'vendor')
mkdirSync(vendorDir, { recursive: true })
// 清掉历史暂存：vendor 里只允许存在本次要打进去的那一个 jar
for (const entry of readdirSync(vendorDir)) {
  if (entry.endsWith('.jar')) rmSync(join(vendorDir, entry))
}
copyFileSync(source, join(vendorDir, jarName))
console.log(`staged ${jarName} → desktop-shell/vendor/`)
