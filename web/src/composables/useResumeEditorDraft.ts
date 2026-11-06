/**
 * 简历编辑器的本地草稿（崩溃或误关页面后的恢复），按 `userId + resumeId` 存 localStorage。
 *
 * 给第一天来的你：编辑简历时用户可能改了半天没点「保存版本」就崩了/关页了，
 * 本模块把未发布的编辑内容实时写到 localStorage，下次打开编辑器时问用户「要恢复吗」。
 *
 * 一条完整生命线（各函数在流程中的位置）：
 * ① 打开编辑器 → read()：从 localStorage 读草稿，版本对得上就放进 restoreCandidate 等用户决定；
 * ② 用户点恢复 → restore()：把草稿内容写回编辑器各输入框，并清掉候选；
 * ③ 编辑过程中 → watch(dirty) 触发 persist()：600ms 防抖写盘；
 * ④ 保存版本或用户放弃 → clear()：删掉草稿键。
 *
 * 边界与不变量：
 * - 只保存「尚未发布的编辑内容」，并用 baseVersionId 绑定基线版本：read() 比对到版本不一致就丢弃，
 *   否则会把旧版本的内容恢复到新版本上（这是本模块最关键的一道防线）；
 * - 写入是 600ms 防抖且仅在 dirty 为 true 时进行，卸载时清掉未触发的定时器；
 * - 所有 localStorage 访问都包 try/catch：隐私模式或禁用存储时会直接抛错，此时功能静默降级
 *   （草稿没了，但编辑器本身照常工作）。
 */
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
    // 未登录或版本号未知时无法判断草稿是否过期，直接不读
    if (!key || baseVersionId.value == null) return
    try {
      const saved = JSON.parse(localStorage.getItem(key) ?? 'null') as ResumeEditorDraft | null
      // 版本不一致的草稿视为失效：恢复到新版本会造成内容错位
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
    // 缺少身份或版本号、或内容为空时不写：空内容会覆盖掉一份可恢复的草稿
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

  // 未修改时不写盘，避免只浏览也留下草稿；防抖 600ms 以合并连续输入
  watch([content, summary, activeSection], () => {
    if (!dirty.value) return
    if (timer) window.clearTimeout(timer)
    timer = window.setTimeout(persist, 600)
  })
  onBeforeUnmount(() => { if (timer) window.clearTimeout(timer) })

  return { restoreCandidate, read, restore, clear, clearFor, reset }
}
