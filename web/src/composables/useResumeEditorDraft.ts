import { onBeforeUnmount, ref, watch, type Ref } from 'vue'

export interface ResumeEditorDraft {
  baseVersionId: number
  content: string
  summary: string
  activeSection: string
  updatedAt: string
}

function keyFor(userId: number | undefined, resumeId: string) {
  return userId ? `intelligent-resume.editor-draft.${userId}.${resumeId}` : null
}

export function useResumeEditorDraft(
  userId: Ref<number | undefined>,
  resumeId: Ref<string>,
  baseVersionId: Ref<number | null>,
  content: Ref<string>,
  summary: Ref<string>,
  activeSection: Ref<string>,
  dirty: Ref<boolean>,
) {
  const restoreCandidate = ref<ResumeEditorDraft | null>(null)
  let timer: ReturnType<typeof window.setTimeout> | undefined

  function read() {
    const key = keyFor(userId.value, resumeId.value)
    if (!key || baseVersionId.value == null) return
    try {
      const saved = JSON.parse(localStorage.getItem(key) ?? 'null') as ResumeEditorDraft | null
      restoreCandidate.value = saved?.baseVersionId === baseVersionId.value ? saved : null
    } catch {
      restoreCandidate.value = null
    }
  }

  function clearFor(targetResumeId: string) {
    if (timer) window.clearTimeout(timer)
    timer = undefined
    const key = keyFor(userId.value, targetResumeId)
    try { if (key) localStorage.removeItem(key) } catch { /* storage is optional */ }
    if (targetResumeId === resumeId.value) restoreCandidate.value = null
  }

  function clear() { clearFor(resumeId.value) }

  function reset() {
    if (timer) window.clearTimeout(timer)
    timer = undefined
    restoreCandidate.value = null
  }

  function persist() {
    const key = keyFor(userId.value, resumeId.value)
    if (!key || baseVersionId.value == null || !content.value) return
    const draft: ResumeEditorDraft = {
      baseVersionId: baseVersionId.value,
      content: content.value,
      summary: summary.value,
      activeSection: activeSection.value,
      updatedAt: new Date().toISOString(),
    }
    try { localStorage.setItem(key, JSON.stringify(draft)) } catch { /* storage is optional */ }
  }

  function restore() {
    const draft = restoreCandidate.value
    if (!draft) return null
    content.value = draft.content
    summary.value = draft.summary
    activeSection.value = draft.activeSection
    restoreCandidate.value = null
    return draft.activeSection
  }

  watch([content, summary, activeSection], () => {
    if (!dirty.value) return
    if (timer) window.clearTimeout(timer)
    timer = window.setTimeout(persist, 600)
  })
  onBeforeUnmount(() => { if (timer) window.clearTimeout(timer) })

  return { restoreCandidate, read, restore, clear, clearFor, reset }
}
