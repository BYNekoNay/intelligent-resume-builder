/**
 * AI 任务轮询 composable。
 *
 * 统一处理 AI 任务状态轮询的公共逻辑：
 * - 每 N 秒轮询一次，达到最大尝试次数后触发超时回调（默认 150 次 × 2s = 5 分钟，
 *   对齐后端 BAILIAN_READ_TIMEOUT_S=300 的推理窗口）；
 * - 轮询代数（epoch）机制：重新 start 或调用 stop 会使旧轮询立即失效，
 *   防止旧轮询污染新任务；
 * - 组件卸载时自动清理定时器，避免内存泄漏与卸载后的状态写入。
 *
 * 视图只需提供 fetchTask 与 onTask/shouldStop/onTimeout/onError 回调，
 * 不再各自维护 timer/attempts/清理逻辑。
 */
import { onUnmounted } from 'vue'

export const TASK_POLL_DEFAULT_INTERVAL_MS = 2_000
export const TASK_POLL_DEFAULT_MAX_ATTEMPTS = 150
export const TASK_POLL_DEFAULT_INITIAL_DELAY_MS = 1_500

export interface TaskPollingOptions<T> {
  /** 要轮询的任务 ID */
  taskId: number
  /** 拉取任务最新状态的函数 */
  fetchTask: (taskId: number) => Promise<T>
  /** 轮询间隔（毫秒），默认 2000 */
  intervalMs?: number
  /** 最大尝试次数，默认 150（约 5 分钟，对齐后端 300s 推理窗口） */
  maxAttempts?: number
  /** 首次轮询延迟（毫秒），默认 1500 */
  initialDelayMs?: number
  /** 每次成功拉取后回调（在 shouldStop 之前调用） */
  onTask?: (task: T) => void
  /** 返回 true 表示任务已到达终态，停止轮询 */
  shouldStop?: (task: T) => boolean
  /** 达到最大尝试次数仍未结束时回调 */
  onTimeout?: () => void
  /** 单次拉取失败时回调（轮询继续，直到超时或被显式 stop） */
  onError?: (error: unknown) => void
}

export function useTaskPolling<T>() {
  let timer: ReturnType<typeof setTimeout> | null = null
  let generation = 0
  let stopped = true

  /** 停止当前轮询并使所有在途回调失效 */
  function stop() {
    generation += 1
    stopped = true
    if (timer !== null) {
      clearTimeout(timer)
      timer = null
    }
  }

  /**
   * 启动轮询。重复调用会先停止旧轮询，保证同一时刻只有一个轮询在跑。
   */
  function start(options: TaskPollingOptions<T>) {
    stop()
    const currentGeneration = generation
    stopped = false
    const intervalMs = options.intervalMs ?? TASK_POLL_DEFAULT_INTERVAL_MS
    const maxAttempts = options.maxAttempts ?? TASK_POLL_DEFAULT_MAX_ATTEMPTS
    const initialDelayMs = options.initialDelayMs ?? TASK_POLL_DEFAULT_INITIAL_DELAY_MS
    let attempts = 0

    const poll = async () => {
      if (stopped || currentGeneration !== generation) return
      attempts += 1
      if (attempts > maxAttempts) {
        stop()
        options.onTimeout?.()
        return
      }
      try {
        const task = await options.fetchTask(options.taskId)
        if (stopped || currentGeneration !== generation) return
        options.onTask?.(task)
        if (options.shouldStop?.(task) ?? false) {
          stop()
          return
        }
      } catch (error) {
        if (stopped || currentGeneration !== generation) return
        options.onError?.(error)
      }
      timer = setTimeout(poll, intervalMs)
    }

    timer = setTimeout(poll, initialDelayMs)
  }

  onUnmounted(() => stop())

  return { start, stop }
}
