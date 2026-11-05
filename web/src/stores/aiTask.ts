/**
 * 全局 AI 任务状态：保存「当前正在跟进的异步任务」并负责轮询。
 * 边界：任务 id 按用户维度存在 localStorage（storageKey 带 userId），所以未登录时不记住也不恢复；
 * 每个请求发出前记下当时的 storageKey，响应回来若 key 已变（切换账号/退出）就丢弃结果。
 * 轮询只在任务未进入终态时继续；SUCCESS 但 confirmationStatus 仍是 PENDING 的任务也要恢复，因为还需要用户确认结果。
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
          polling.value = false
          return
        }
        if (TERMINAL_STATUSES.has(task.status)) {
          polling.value = false
          return
        }
        attempt += 1
        // 退避节奏固定为 1s/2s/4s/5s，第 4 次之后恒定 5s，避免长任务把后端打满
        const delay = [1000, 2000, 4000, 5000][Math.min(attempt - 1, 3)]
        timer = window.setTimeout(tick, delay)
      } catch {
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
