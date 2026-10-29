/**
 * 后端业务错误码 → 前端 i18n 文案键（TC-3：错误码→文案集中映射）。
 *
 * 后端 ErrorCode 枚举（server/src/main/java/com/intelligentresume/common/error/ErrorCode.java）
 * 与此表的码值必须保持同步：后端新增码时在此登记；未登记的码由调用方的
 * fallback 文案兜底（resolveApiError），不会透传服务端 message——服务端 message
 * 仅中文，透传会破坏 en-US 界面。
 *
 * 本文件保持零依赖（纯常量 + 纯函数）。测试在 `web/tests/unit/errorCodes.test.ts`（vitest，已进 CI）；
 * 该测试会**直接读后端 ErrorCode.java 对账**，两边任一侧漏加码都会变红。
 * （原先的 `web/scripts/errorCodes.test.mjs` 没有接入任何 script/CI，已随批次 3 迁走。）
 */
export const ERROR_CODE_KEYS: Readonly<Record<number, string>> = {
  40001: 'errors.validation',
  40101: 'errors.unauthenticated',
  40301: 'errors.forbidden',
  40302: 'errors.consentRequired',
  40401: 'errors.notFound',
  40901: 'errors.conflict',
  40902: 'errors.versionArchived',
  40303: 'errors.accountDeletionPending',
  40903: 'errors.accountDeletionWindowExpired',
  40904: 'errors.selectionItemUnknown',
  42901: 'errors.rateLimited',
  50001: 'errors.internal',
  50002: 'errors.aiFailure',
  50003: 'errors.pdfFailure',
}

interface ApiResponseShape {
  code?: unknown
  message?: unknown
  data?: unknown
  traceId?: unknown
}

interface ApiErrorLike {
  response?: { data?: ApiResponseShape }
  code?: unknown
}

/**
 * 从异常中提取后端业务错误码。**唯一入口**——视图不要再去掏 `error.response.data.code`。
 *
 * 两种形态都要认（2026-10-29 改造后同时存在，缺一种就会静默失效）：
 * 1. **`ApiError`（HTTP 2xx + `code !== 0`）**：`client.ts` 的 `request()` 解信封时抛出，
 *    业务码在 `error.code`，traceId 也在对象上；
 * 2. **`AxiosError`（HTTP 4xx/5xx）**：后端把业务码放在响应体顶层（`response.data.code`），
 *    axios 因此走 reject 分支。
 *
 * 提取不到（网络错误 / 非 ApiResponse / 码非法）返回 null，由调用方 fallback 文案兜底。
 */
export function extractErrorCode(cause: unknown): number | null {
  if (cause == null || typeof cause !== 'object') return null
  const candidate = cause as ApiErrorLike
  const direct = candidate.code
  if (typeof direct === 'number' && Number.isFinite(direct) && direct !== 0) {
    return direct
  }
  const raw = candidate.response?.data?.code
  return typeof raw === 'number' && Number.isFinite(raw) ? raw : null
}

/** 从异常中取 traceId（`ApiError` 自带；`AxiosError` 从信封里取）。取不到返回 null。 */
export function extractTraceId(cause: unknown): string | null {
  if (cause == null || typeof cause !== 'object') return null
  const candidate = cause as ApiErrorLike & { traceId?: unknown }
  if (typeof candidate.traceId === 'string' && candidate.traceId) return candidate.traceId
  const raw = candidate.response?.data?.traceId
  return typeof raw === 'string' && raw ? raw : null
}
