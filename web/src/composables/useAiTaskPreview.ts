import { computed, ref } from 'vue'
import { previewAiTask, type AiTaskPreview } from '@/api/ai'
import { useLocale } from '@/i18n'
import { resolveApiError } from '@/utils/errorMessage'

/**
 * 发送前预览的确认流（R32）。
 *
 * <p>把「拉预览 → 给用户看 → 等用户决定」收成一个可 await 的调用：
 * ```ts
 * const token = await requestPreview('MATERIAL_IMPORT', input)
 * if (!token) return   // 用户取消：一条都不发
 * ```
 * 这样调用方**没有别的途径**拿到令牌 —— 想发就必须先看完预览并确认。
 */
export function useAiTaskPreview() {
  const { t } = useLocale()

  const visible = ref(false)
  const loading = ref(false)
  const errorMessage = ref<string | null>(null)
  const preview = ref<AiTaskPreview | null>(null)

  let pendingTaskType = ''
  let pendingInput: Record<string, unknown> = {}
  let resolver: ((token: string | null) => void) | null = null

  const redactionSummary = computed(() =>
    (preview.value?.redactions ?? []).map(item => ({
      category: item.category,
      label: item.category === 'EMAIL' ? t('aiPreview.contactEmail') : t('aiPreview.contactPhone'),
      count: item.count,
    })),
  )

  function requestPreview(taskType: string, input: Record<string, unknown>, includeContact = false) {
    pendingTaskType = taskType
    pendingInput = input
    // 上一次没走完就再次发起时，先解开旧等待方，避免调用方永远挂着
    resolver?.(null)
    resolver = null

    const decided = new Promise<string | null>(resolve => {
      resolver = resolve
    })
    void load(includeContact)
    return decided
  }

  async function load(includeContact: boolean) {
    visible.value = true
    loading.value = true
    errorMessage.value = null
    try {
      preview.value = await previewAiTask(pendingTaskType, pendingInput, includeContact)
    } catch (error) {
      preview.value = null
      errorMessage.value = resolveApiError(error, 'aiPreview.failed')
    } finally {
      loading.value = false
    }
  }

  function confirm() {
    settle(preview.value?.previewToken ?? null)
  }

  /** 取消 = 明确不发（不是"没点"）：调用方拿到 null 就该直接 return。 */
  function cancel() {
    settle(null)
  }

  function toggleContact() {
    if (!preview.value) return
    void load(!preview.value.includeContact)
  }

  function settle(token: string | null) {
    visible.value = false
    const resolve = resolver
    resolver = null
    resolve?.(token)
  }

  return { visible, loading, errorMessage, preview, redactionSummary, requestPreview, confirm, cancel, toggleContact }
}
