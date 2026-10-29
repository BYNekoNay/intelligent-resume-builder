#!/usr/bin/env node
/**
 * 首屏体积预算门禁（2026-10-29）。
 *
 * 为什么需要它：主包体积是**唯一每个用户都付的成本**，而它只增不减——
 * 加一个依赖、多一个静态 import，没人会收到任何信号。
 *
 * 判据是「首屏真实加载集」而不是「某个 chunk 的大小」：
 * 直接解析 `dist/index.html` 里的 `script` / `modulepreload` / `stylesheet` 链接，
 * 逐个 gzip 后求和。理由：分包策略一变，intuition 里的"主包"就不再等于用户实际下载的东西；
 * 只有 html 里的引用列表是浏览器真正会拉的。
 *
 * 阈值是**棘轮**（只许降不许升）：
 *   - 每次有意减少后就下调 EXPECTED_*；
 *   - 无意增长则立即变红，作者必须解释这笔预算花在哪。
 * 阈值取「当前实测 + 3% 余量」，不使用理想值——理想值会让门禁长期红着，然后被无视。
 */
import { readFileSync, existsSync } from 'node:fs'
import { gzipSync } from 'node:zlib'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const projectRoot = join(dirname(fileURLToPath(import.meta.url)), '..')
const distDir = join(projectRoot, 'dist')
const indexPath = join(distDir, 'index.html')

/** 首屏 JS 总量上限（KB，gzip -9）。2026-10-29（i18n 拆分后）实测 101.65 KB（不含 CSS）。 */
const EXPECTED_INITIAL_JS_KB = 105
/** 首屏 JS + CSS 总量上限（KB，gzip -9）。2026-10-29（i18n 拆分后）实测 115.54 KB。 */
const EXPECTED_INITIAL_TOTAL_KB = 120
/** 入口 chunk（index-*.js）上限（KB，gzip -9）。2026-10-29（i18n 拆分后）实测 35.27 KB。 */
const EXPECTED_ENTRY_CHUNK_KB = 38

if (!existsSync(indexPath)) {
  console.error('[bundle-budget] 找不到 dist/index.html —— 必须先 `npm run build`（不得当作通过）')
  process.exit(1)
}

const html = readFileSync(indexPath, 'utf8')
const assets = [...new Set([...html.matchAll(/(?:src|href)="(\/assets\/[^"]+\.(?:js|css))"/g)].map(m => m[1]))].sort()

if (assets.length === 0) {
  console.error('[bundle-budget] index.html 里没有解析到任何 /assets/ 引用 —— 解析失效（不得当作通过）')
  process.exit(1)
}

const kb = bytes => bytes / 1024
let jsTotal = 0
let cssTotal = 0
let entryBytes = 0
const rows = []

for (const asset of assets) {
  const file = join(distDir, asset.replace(/^\//, ''))
  if (!existsSync(file)) {
    console.error(`[bundle-budget] index.html 引用了不存在的文件：${asset}`)
    process.exit(1)
  }
  const gz = gzipSync(readFileSync(file), { level: 9 }).length
  if (asset.endsWith('.js')) jsTotal += gz
  else cssTotal += gz
  if (/\/index-[^/]+\.js$/.test(asset)) entryBytes = gz
  rows.push(`  ${kb(gz).toFixed(2).padStart(8)} KB  ${asset}`)
}

const total = jsTotal + cssTotal
const failures = []

if (kb(jsTotal) > EXPECTED_INITIAL_JS_KB) {
  failures.push(`首屏 JS ${kb(jsTotal).toFixed(2)} KB 超过预算 ${EXPECTED_INITIAL_JS_KB} KB`)
}
if (kb(total) > EXPECTED_INITIAL_TOTAL_KB) {
  failures.push(`首屏合计 ${kb(total).toFixed(2)} KB 超过预算 ${EXPECTED_INITIAL_TOTAL_KB} KB`)
}
if (entryBytes === 0) {
  failures.push('index.html 中没有入口 chunk（index-*.js）—— 分包结构变了，请同步本门禁的判据')
} else if (kb(entryBytes) > EXPECTED_ENTRY_CHUNK_KB) {
  failures.push(`入口 chunk ${kb(entryBytes).toFixed(2)} KB 超过预算 ${EXPECTED_ENTRY_CHUNK_KB} KB`)
}

console.log('[bundle-budget] 首屏加载集（gzip -9）：')
rows.forEach(row => console.log(row))
console.log(`  首屏 JS ${kb(jsTotal).toFixed(2)} KB / +CSS ${kb(total).toFixed(2)} KB / 入口 chunk ${kb(entryBytes).toFixed(2)} KB`)

if (failures.length > 0) {
  console.error('\n[bundle-budget] guard FAILED：')
  failures.forEach(f => console.error(`  - ${f}`))
  console.error('  说明：这是棘轮门禁。确属有意增长时，先说明预算花在哪，再上调 EXPECTED_*_KB。')
  process.exit(1)
}

console.log('[bundle-budget] guard passed')
