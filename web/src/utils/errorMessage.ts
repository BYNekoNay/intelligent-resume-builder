import { useLocale } from '@/i18n'
import { ERROR_CODE_KEYS, extractErrorCode } from '@/utils/errorCodes'

/**
 * API 错误 → 用户可见文案（TC-3）。
 *
 * 规则：后端业务码有登记文案则用登记文案；否则回退调用方的场景文案。
 * 有意**不透传服务端 message**——服务端 message 仅中文，直接透传会破坏
 * en-US 界面（这正是 2026-09-26 盘点报告 TC-3 指出的「吞 message」问题的
 * 双语化解法：错误码映射，而非字符串透传）。
 */
export function resolveApiError(cause: unknown, fallbackKey: string): string {
  const { t } = useLocale()
  const code = extractErrorCode(cause)
  const registeredKey = code != null ? ERROR_CODE_KEYS[code] : undefined
  return t(registeredKey ?? fallbackKey)
}
