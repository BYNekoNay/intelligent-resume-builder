/**
 * 后端业务错误码 → 前端 i18n 文案键（TC-3：错误码→文案集中映射）。
 *
 * 后端 ErrorCode 枚举（server/src/main/java/com/intelligentresume/common/error/ErrorCode.java）
 * 与此表的码值必须保持同步：后端新增码时在此登记；未登记的码由调用方的
 * fallback 文案兜底（resolveApiError），不会透传服务端 message——服务端 message
 * 仅中文，透传会破坏 en-US 界面。
 *
 * 本文件保持零依赖（纯常量 + 纯函数），web/scripts/errorCodes.test.mjs 通过
 * node --experimental-strip-types 直接测试本文件。
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
  42901: 'errors.rateLimited',
  50001: 'errors.internal',
  50002: 'errors.aiFailure',
  50003: 'errors.pdfFailure',
}

interface ApiResponseShape {
  code?: unknown
  message?: unknown
  data?: unknown
}

interface ApiErrorLike {
  response?: { data?: ApiResponseShape }
  code?: unknown
}

/**
 * 从 axios 错误中提取后端业务错误码。
 * ApiResponse 包装层把 code 放在响应体顶层，因此 axios 的 error.response.data.code
 * 即业务码；提取不到（网络错误 / 非 ApiResponse / 码非法）返回 null。
 */
export function extractErrorCode(cause: unknown): number | null {
  if (cause == null || typeof cause !== 'object') return null
  const candidate = cause as ApiErrorLike
  const raw = candidate.response?.data?.code
  return typeof raw === 'number' && Number.isFinite(raw) ? raw : null
}
