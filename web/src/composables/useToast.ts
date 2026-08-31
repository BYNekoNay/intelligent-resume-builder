/**
 * 轻量 toast composable（无第三方依赖）。
 *
 * <p>全局单例队列：{ type: 'success' | 'error' | 'info', message }，
 * 自动 3 秒消失，最多同时展示 4 条（先进先出）。
 * 视图通过 {@link useToast} 获取 toasts 与 push 方法。
 */
import { ref } from 'vue'

export type ToastType = 'success' | 'error' | 'info'

export interface ToastItem {
  id: number
  type: ToastType
  message: string
}

const MAX_TOASTS = 4
const TOAST_TTL_MS = 3000

const toasts = ref<ToastItem[]>([])
let nextId = 1

function push(type: ToastType, message: string) {
  const id = nextId++
  toasts.value.push({ id, type, message })
  if (toasts.value.length > MAX_TOASTS) {
    toasts.value.shift()
  }
  window.setTimeout(() => {
    toasts.value = toasts.value.filter((item) => item.id !== id)
  }, TOAST_TTL_MS)
}

export function useToast() {
  function success(message: string) { push('success', message) }
  function error(message: string) { push('error', message) }
  function info(message: string) { push('info', message) }
  function dismiss(id: number) {
    toasts.value = toasts.value.filter((item) => item.id !== id)
  }
  return { toasts, success, error, info, dismiss }
}
