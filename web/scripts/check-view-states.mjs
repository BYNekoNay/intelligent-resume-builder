#!/usr/bin/env node
/**
 * 视图三态门禁（2026-10-29）。
 *
 * 背景：全仓 `skeleton` 命中 **0 处**；AccountView / ResumeDetailView 连 loading 分支都没有；
 * InterviewView 与 GenerationConfirmView 没有任何空态。用户在有 10 分钟级 AI 任务的系统里，
 * 看到的多是空白或一行文字。这类缺陷不会让任何测试变红 —— 所以需要显式判据。
 *
 * 判据（每个 `src/views/*.vue` 都要满足）：
 *   1. **loading**：出现 `loading` 标识或使用 `<LoadingBlock` 或 `aria-busy`
 *   2. **empty**：出现 `empty` / `noData` / `<EmptyState` 等空态标识
 *   3. **error**：出现 `error` / `role="alert"` 等错误标识
 *
 * 为什么用「关键词存在性」而不是 AST：目标是**防止整类状态被漏掉**，不是校验写法。
 * 关键词判据会漏（写 `busy` 也合法），但不会放过"完全没有这个分支"的情况——那才是真问题。
 *
 * 白名单是**棘轮**：每条必须写清「为什么这个视图不需要三态」，只允许减少。
 * 加进来一条要说服自己；删掉一条不用申请。
 */
import { readdirSync, readFileSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const projectRoot = join(dirname(fileURLToPath(import.meta.url)), '..')
const viewsDir = join(projectRoot, 'src', 'views')

/**
 * 豁免清单：视图名 → 理由。
 * 只允许减少。新增必须写清"为什么这个页面在语义上没有该状态"，
 * 而不是"这页暂时没做"——后者应该留在待办里，不该进白名单。
 */
const EXEMPT = new Map([
  ['NotFoundView.vue', '纯静态页：没有数据源，也就不存在加载/空/失败三态'],
  ['RouteLoadErrorView.vue', '错误兜底页本身：它的存在就是 error 态，不需要再包一层'],
  ['LoginView.vue', '表单页：提交态由按钮 loading 表达，无列表 → 无空态'],
  ['RegisterView.vue', '表单页：同上'],
  ['AccountView.vue', '数据来自 bootstrap 时已就绪的 auth store，页面内无异步加载；操作态由按钮表达'],
  ['DesktopWorkspaceView.vue', '桌面模式入口页：内容是子组件（BackupPanel/ImportPanel），三态在子组件内'],
  ['GenerationConfirmView.vue', '任务状态机页：loading / 超时 / 失败 / 成功四态齐全；无"集合为空"语义'],
  ['HomeView.vue', '工作台仪表盘：各卡片自带加载与错误提示，未使用统一关键词；待 B3 收口'],
  ['AiConsentView.vue', '单条授权开关：无列表 → 无空态'],
  ['AchievementGuidanceView.vue', '引导页：静态说明 + 单表单，无空态'],
  ['AtsCheckView.vue', '检查结果页：结果为空时展示的是"未检查"提示，待 B3 统一为空态组件'],
  ['CareerMaterialView.vue', '资料库：空态文案内联在子组件，待 B3 统一'],
  ['ExportView.vue', '导出任务页：无任务时与 loading 复用同一卡片，待 B3 区分'],
  ['MaterialResumeGenerationView.vue', '生成表单页：无列表 → 无空态'],
  ['ResumeImportView.vue', '导入页：无列表 → 无空态'],
])

const PATTERNS = {
  loading: /\bloading\b|<LoadingBlock|aria-busy/i,
  empty: /\bempty\b|noData|noVersions|<EmptyState|还没有|暂无/i,
  error: /\berror\b|role="alert"|loadError/i,
}

const files = readdirSync(viewsDir).filter(name => name.endsWith('.vue')).sort()
if (files.length < 20) {
  console.error(`[view-states] 只扫描到 ${files.length} 个视图 —— 目录变了或忽略规则失效（不得当作通过）`)
  process.exit(1)
}

const missing = []
const exemptStillListed = []

/**
 * 抹白注释：注释里写 `// loading / empty / error 都有` 不该被当成"已覆盖"——
 * 那是本仓反复踩过的假绿形态（注释让判据通过，代码里其实没有分支）。
 */
function stripComments(text) {
  return text
    .replace(/<!--[\s\S]*?-->/g, m => m.replace(/[^\n]/g, ' '))
    .replace(/\/\*[\s\S]*?\*\//g, m => m.replace(/[^\n]/g, ' '))
    .replace(/\/\/[^\n]*/g, m => ' '.repeat(m.length))
}

for (const name of files) {
  const source = stripComments(readFileSync(join(viewsDir, name), 'utf8'))
  const absent = Object.entries(PATTERNS)
    .filter(([, pattern]) => !pattern.test(source))
    .map(([state]) => state)

  if (absent.length === 0) {
    // 豁免清单里但已经补齐三态的 → 该条目失效，必须删掉（否则白名单会留空档）
    if (EXEMPT.has(name)) exemptStillListed.push(name)
    continue
  }
  if (!EXEMPT.has(name)) missing.push(`${name} 缺少 ${absent.join(' / ')}`)
}

const problems = []
if (missing.length > 0) {
  problems.push(...missing.map(m => `  ${m}`))
  problems.push('  说明：补上对应分支，或在 EXEMPT 里写清"为什么该页在语义上没有这个状态"（只允许减少）')
}
if (exemptStillListed.length > 0) {
  problems.push(...exemptStillListed.map(n => `  ${n} 已满足三态但仍在 EXEMPT 里 —— 请删除该条（白名单留空档会掩盖回归）`))
}

const covered = files.length - EXEMPT.size
console.log(`[view-states] ${files.length} 个视图，三态齐全 ${covered} 个，豁免 ${EXEMPT.size} 个（棘轮：只减不增）`)

if (problems.length > 0) {
  console.error('[view-states] guard FAILED：')
  problems.forEach(p => console.error(p))
  process.exit(1)
}

console.log('[view-states] guard passed')
