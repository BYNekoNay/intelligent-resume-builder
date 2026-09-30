/**
 * 浏览器本地用户数据清理（第四十二批）。
 *
 * <p>背景：编辑器草稿把**完整简历 JSON**（姓名/联系方式/工作经历等 PII）写入
 * `localStorage['intelligent-resume.editor-draft.<userId>.<resumeId>']` 以便崩溃恢复；
 * 而「退出登录」只清内存里的 access token、「删除账号」只清会话，两条路径都不碰本地存储——
 * 于是共享设备上换人使用时，上一账号的简历原文仍留在浏览器里；用户删号后同样如此，
 * 与 `docs/08` §9.6「账户删除…完成清理」的口径不符。
 *
 * <p>清理范围（本地存储的完整清单，新增用户级键时必须在此登记）：
 * <ul>
 *   <li>localStorage：`editor-draft`（简历/编辑草稿，含 PII）、`active-ai-task`（待恢复的 AI 任务 id）；</li>
 *   <li>sessionStorage：`resume-import-text`（导入解析出的简历原文）、`application-draft`
 *       （沟通/投递文案草稿）、`pending-job-generation`（待确认的生成任务）、
 *       `interview-session-id`（当前面试会话 id）。</li>
 * </ul>
 *
 * <p>刻意**不清理**用户无关的键（界面语言、编辑器侧栏折叠状态）：它们不含用户数据，
 * 清掉只会丢偏好。
 */

/** localStorage 中按用户隔离的键前缀：`intelligent-resume.<prefix>.<userId>[.<rest>]`。 */
const LOCAL_USER_SCOPED_PREFIXES = ['editor-draft', 'active-ai-task']

/** sessionStorage 中的用户数据键（单标签页单用户，键名不带 userId）。 */
const SESSION_USER_KEYS = [
  'resume-import-text',
  'application-draft',
  'pending-job-generation',
  'interview-session-id',
]

/**
 * 清理当前账号在本浏览器留下的持久化数据。
 *
 * @param userId 当前账号 id；缺失（如会话未加载完成）时只清理标签页级数据
 */
export function clearUserLocalData(userId?: number | null): void {
  clearLocalStorage(userId)
  clearSessionStorage()
}

function clearLocalStorage(userId?: number | null): void {
  if (userId == null || !Number.isFinite(userId)) return
  try {
    const owned = new RegExp(
      `^intelligent-resume\\.(?:${LOCAL_USER_SCOPED_PREFIXES.join('|')})\\.${userId}(?:\\.|$)`,
    )
    const keys: string[] = []
    for (let index = 0; index < window.localStorage.length; index += 1) {
      const key = window.localStorage.key(index)
      if (key && owned.test(key)) keys.push(key)
    }
    keys.forEach(key => window.localStorage.removeItem(key))
  } catch {
    /* storage is optional（隐私模式/被禁用时静默跳过） */
  }
}

function clearSessionStorage(): void {
  try {
    SESSION_USER_KEYS.forEach(key => window.sessionStorage.removeItem(key))
  } catch {
    /* storage is optional */
  }
}
