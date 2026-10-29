#!/usr/bin/env node
/**
 * 传输层收口门禁（2026-10-29）。
 *
 * 背景：改造前 api 层与视图层各解一层信封，`web/src` 里 `.data.data` 出现 **123 次**，
 * 每个视图还要自己判断成败、自己拼 fallback 文案；后端错误码体系在 UI 侧被整体压平。
 * 改造后约定是**两条**，任何一条被破坏都会让错误处理重新散落：
 *
 *   1. 信封只在 `src/api/client.ts` 里解 —— 其它文件出现 `.data.data` 即红；
 *   2. 业务码只经 `extractErrorCode()` 读取 —— 其它文件直接掏 `response?.data?.code` 即红
 *      （否则 ApiError 形态会被漏掉：HTTP 2xx + code≠0 时压根没有 response）。
 *
 * 判据是「禁止形态」而非「必须有形态」：将来谁换了写法但仍在视图里解包，一样会红。
 * 注释行不参与（否则说明性注释会误伤）——本仓其它门禁同样先抹白注释。
 */
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const projectRoot = join(dirname(fileURLToPath(import.meta.url)), '..')
const srcDir = join(projectRoot, 'src')

/** 信封解包只允许出现在这里（它是唯一知道 ApiResponse 形状的地方）。 */
const UNWRAP_ALLOWED = ['src/api/client.ts']
/** 业务码读取的唯一入口。 */
const CODE_EXTRACT_ALLOWED = [
  'src/utils/errorCodes.ts',
  // 拦截器层：信封还没解（这正是它要判 401 的原因），且 errorCodes.ts 不能再反向依赖 client，
  // 会形成循环。这里只判 40101 一个码，属于"识别未认证"而非"业务错误映射"。
  'src/api/client.ts',
]

const BANNED = [
  {
    name: '信封解包',
    pattern: /\.data\.data/g,
    allowed: UNWRAP_ALLOWED,
    hint: 'api 函数已返回裸 payload；请直接用返回值（解包只在 api/client.ts 的 request() 里做）',
  },
  {
    name: '直接读业务码',
    pattern: /response\??\.data\??\.code/g,
    allowed: CODE_EXTRACT_ALLOWED,
    hint: '请改用 extractErrorCode(cause) —— 它同时认识 ApiError（2xx + code≠0）与 AxiosError',
  },
]

function collectFiles(dir) {
  const out = []
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry)
    if (statSync(full).isDirectory()) out.push(...collectFiles(full))
    else if (/\.(ts|vue)$/.test(entry)) out.push(full)
  }
  return out
}

/** 抹白行注释与块注释（保留换行与长度，行号不漂移）。 */
function stripComments(text) {
  return text
    .replace(/\/\*[\s\S]*?\*\//g, m => m.replace(/[^\n]/g, ' '))
    .replace(/\/\/[^\n]*/g, m => ' '.repeat(m.length))
}

const files = collectFiles(srcDir)
if (files.length < 50) {
  console.error(`[api-unwrap] 只扫描到 ${files.length} 个源文件 —— 目录结构变了或忽略规则失效（不得当作通过）`)
  process.exit(1)
}

const violations = []
for (const file of files) {
  const rel = relative(projectRoot, file).replace(/\\/g, '/')
  const code = stripComments(readFileSync(file, 'utf8'))
  for (const rule of BANNED) {
    if (rule.allowed.includes(rel)) continue
    const matches = [...code.matchAll(rule.pattern)]
    if (matches.length === 0) continue
    const lineOf = index => code.slice(0, index).split('\n').length
    for (const match of matches) {
      violations.push(`${rel}:${lineOf(match.index)} 出现「${rule.name}」${match[0]} —— ${rule.hint}`)
    }
  }
}

if (violations.length > 0) {
  console.error('[api-unwrap] guard FAILED：')
  violations.slice(0, 20).forEach(v => console.error(`  - ${v}`))
  if (violations.length > 20) console.error(`  …还有 ${violations.length - 20} 处`)
  process.exit(1)
}

console.log(`[api-unwrap] guard passed（扫描 ${files.length} 个文件：无信封解包、无裸业务码读取）`)
