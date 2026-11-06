/**
 * 全局 AI 任务状态：保存「当前正在跟进的异步任务」并负责轮询。
 *
 * 给第一天来的你：这个平台里「AI 生成简历」「选材」「追问练习」等操作都是异步任务——
 * 后端立即返回一个任务 id，真正结果要等 AI 跑完。本 store 就负责记住「用户正在等的那个任务」，
 * 定时去后端问状态，直到任务进入终态（SUCCESS / FAILED / CANCELLED）。
 *
 * 使用方：GenerationWorkbenchView（发起生成）、GenerationConfirmView / MaterialSelectionConfirmView
 * （生成结果的确认页）。
 *
 * 持久化：只把「任务 id」按用户维度存 localStorage（键 `intelligent-resume.active-ai-task.<userId>`，
 * 见 TASK_STORAGE_PREFIX），任务本体只在内存。这样刷新页面后还能恢复跟进，且换账号互不串扰。
 *
 * 易错点（读代码前先记住这三条）：
 * - 任务 id 按用户维度存 localStorage，所以未登录时不记住也不恢复；
 * - 每个请求发出前记下当时的 storageKey，响应回来若 key 已变（切换账号/退出）就丢弃结果——
 *   这是竞态保护：迟到的旧账号响应绝不能写进新账号的界面；
 * - 轮询只在任务未进入终态时继续；SUCCESS 但 confirmationStatus 仍是 PENDING 的任务也要恢复，
 *   因为还需要用户确认结果（确认页要靠它重新打开）。
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getTask, type AiTask } from '@/api/ai'
import { useLocale } from '@/i18n'
import { useAuthStore } from '@/stores/auth'

const TASK_STORAGE_PREFIX = 'intelligent-resume.active-ai-task'
const TERMINAL_STATUSES = new Set<AiTask['status']>(['SUCCESS', 'FAILED', 'CANCELLED'])

export const useAiTaskStore = defineStore('ai-task', () => {
  const { t } = useLocale()
  const current = ref<AiTask | null>(null)
  const polling = ref(false)
  const error = ref<string | null>(null)
  let timer: number | null = null

  function storageKey() {
    // 当前登录用户的 storageKey；未登录返回 null（此时一切「记住/恢复」都不生效）
    const userId = useAuthStore().currentUser?.id
    return userId ? `${TASK_STORAGE_PREFIX}.${userId}` : null
  }

  function remember(id: number, key = storageKey()) {
    if (key) localStorage.setItem(key, String(id))
  }

  function rememberedId(key = storageKey()) {
    const id = Number(key ? localStorage.getItem(key) : null)
    return Number.isInteger(id) && id > 0 ? id : null
  }

  function forget(key = storageKey()) {
    if (key) localStorage.removeItem(key)
  }

  function needsRecovery(task: AiTask) {
    // 终态且不需要确认的任务才可以从 localStorage 里丢弃
    if (!TERMINAL_STATUSES.has(task.status)) return true
    return task.status === 'SUCCESS' && task.confirmationStatus === 'PENDING'
  }

  function isCurrentStorageKey(key: string | null) {
    return key !== null && storageKey() === key
  }

  async function load(id: number, key = storageKey()) {
    const res = await getTask(id)
    if (!isCurrentStorageKey(key)) return null
    const task = res
    current.value = task
    if (needsRecovery(task)) {
      remember(id, key)
    } else {
      forget(key)
    }
    return task
  }

  async function restore() {
    stopPolling()
    current.value = null
    error.value = null
    const key = storageKey()
    const id = rememberedId(key)
    if (!id) return null
    try {
      return await load(id, key)
    } catch {
      if (!isCurrentStorageKey(key)) return null
      current.value = null
      error.value = 'TASK_STATUS_UNAVAILABLE'
      return null
    }
  }

  /**
   * 启动轮询：立即查一次状态，之后按退避节奏重试直到终态。
   * 步骤：① 先 stopPolling 清掉旧轮询（同一时刻只允许跟一个任务）；
   * ② 记下当时的 storageKey，之后每次响应都校验它，防止账号切换后旧响应写入；
   * ③ tick 拉一次任务 → 未到终态则安排下一次，间隔逐步拉长。
   */
  function startPolling(id: number) {
    stopPolling()
    polling.value = true
    error.value = null
    const key = storageKey()
    let attempt = 0
    const tick = async () => {
      if (!polling.value) return
      try {
        const task = await load(id, key)
        if (!task) {
          // load 返回 null = 响应回来时账号已切换，本次跟进作废
          polling.value = false
          return
        }
        if (TERMINAL_STATUSES.has(task.status)) {
          polling.value = false
          return
        }
        attempt += 1
        // 退避节奏固定为 1s/2s/4s/5s，第 4 次之后恒定 5s：AI 任务前期进展快（密集查能尽快出结果），
        // 后期耗时以分钟计，固定高频只会把后端打满且对用户没有额外收益
        const delay = [1000, 2000, 4000, 5000][Math.min(attempt - 1, 3)]
        timer = window.setTimeout(tick, delay)
      } catch {
        // 单次查询失败（如断网）就整体终止并报错，让用户手动重试，而不是无限重试
        error.value = t('aiTask.pollingError')
        polling.value = false
      }
    }
    void tick()
  }

  function restorePolling() {
    const id = rememberedId()
    if (id) startPolling(id)
  }

  function clear() {
    stopPolling()
    current.value = null
    error.value = null
    forget()
  }

  function stopPolling() {
    polling.value = false
    if (timer !== null) {
      window.clearTimeout(timer)
      timer = null
    }
  }

  return { current, polling, error, load, restore, remember, needsRecovery, startPolling, restorePolling, stopPolling, clear }
})
