/**
 * AI 任务轮询节奏的单一来源（#80）。
 *
 * 此前三套轮询实现（stores/aiTask.ts、api/ai.ts 的 waitForAiTaskResult、
 * api/materialGeneration.ts 的 waitForTask）各自维护一份节奏常量，已经漂移成三种节奏。
 * 统一收敛到「主节奏」：前 4 次查询按退避表密集进行（1s/2s/4s/5s），之后恒定 5s。
 *
 * 取值依据（沿用 stores/aiTask.ts 的既有注释）：AI 任务前期进展快，密集查能尽快出结果；
 * 后期耗时以分钟计，固定高频只会把后端打满且对用户没有额外收益。
 *
 * 注意：composables/useTaskPolling.ts 不在此收敛范围内 —— 它是组件内的通用轮询
 * composable，视图会按场景传自定义 intervalMs（如 AtsCheckView 的 1.5s），强行统一
 * 反而改变多个视图的行为。
 */

/** 退避表走完后的稳态轮询间隔。 */
export const AI_TASK_POLL_STEADY_INTERVAL_MS = 5_000

/** 前 N 次查询的加速退避表：第 attempt 次查询（0 起计）后等待 WARMUP[attempt] 毫秒。 */
const AI_TASK_POLL_WARMUP_DELAYS_MS = [1_000, 2_000, 4_000, 5_000] as const

/** 第 {@link attempt} 次（0 起计）查询后的等待毫秒数：前 4 次按退避表，之后恒定 5s。 */
export function aiTaskPollDelayMs(attempt: number): number {
  return attempt < AI_TASK_POLL_WARMUP_DELAYS_MS.length
    ? AI_TASK_POLL_WARMUP_DELAYS_MS[attempt]
    : AI_TASK_POLL_STEADY_INTERVAL_MS
}
