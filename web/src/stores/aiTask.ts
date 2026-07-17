import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getTask, type AiTask } from '@/api/ai'
import { useAuthStore } from '@/stores/auth'

const TASK_STORAGE_PREFIX = 'intelligent-resume.active-ai-task'
const TERMINAL_STATUSES = new Set<AiTask['status']>(['SUCCESS', 'FAILED', 'CANCELLED'])

export const useAiTaskStore = defineStore('ai-task', () => {
  const current = ref<AiTask | null>(null)
  const polling = ref(false)
  const error = ref<string | null>(null)
  let timer: number | null = null

  function storageKey() {
    const userId = useAuthStore().currentUser?.id
    return userId ? `${TASK_STORAGE_PREFIX}.${userId}` : null
  }

  function remember(id: number) {
    const key = storageKey()
    if (key) localStorage.setItem(key, String(id))
  }

  function forget() {
    const key = storageKey()
    if (key) localStorage.removeItem(key)
  }

  function needsRecovery(task: AiTask) {
    return !TERMINAL_STATUSES.has(task.status)
      || (task.status === 'SUCCESS' && task.confirmationStatus !== 'CONFIRMED' && task.confirmationStatus !== 'REJECTED')
  }

  async function load(id: number) {
    const res = await getTask(id)
    current.value = res.data.data
    if (needsRecovery(current.value)) {
      remember(id)
    } else {
      forget()
    }
    return current.value
  }

  function startPolling(id: number) {
    stopPolling()
    polling.value = true
    error.value = null
    let attempt = 0
    const tick = async () => {
      if (!polling.value) return
      try {
        const task = await load(id)
        if (TERMINAL_STATUSES.has(task.status)) {
          polling.value = false
          return
        }
        attempt += 1
        const delay = [1000, 2000, 4000, 5000][Math.min(attempt - 1, 3)]
        timer = window.setTimeout(tick, delay)
      } catch {
        error.value = '任务状态暂时无法获取，请检查网络后重试。'
        polling.value = false
      }
    }
    void tick()
  }

  function restorePolling() {
    const key = storageKey()
    const id = Number(key ? localStorage.getItem(key) : null)
    if (Number.isInteger(id) && id > 0) startPolling(id)
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

  return { current, polling, error, load, remember, startPolling, restorePolling, stopPolling, clear }
})
