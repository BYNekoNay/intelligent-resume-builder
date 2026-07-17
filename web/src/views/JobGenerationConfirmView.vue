<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Check, Pencil, RefreshCw, X } from 'lucide-vue-next'
import { useAiTaskStore } from '@/stores/aiTask'
import { confirmTask, rejectTask } from '@/api/ai'

const props = defineProps<{ jobId: string }>()
const taskStore = useAiTaskStore()
const route = useRoute()
const router = useRouter()
const submitting = ref(false)
const rejecting = ref(false)
const error = ref('')
const editingPath = ref<string | null>(null)
const editedValue = ref('')
const decisions = ref<Record<string, 'ACCEPT' | 'EDIT' | 'REJECT'>>({})
const editedValues = ref<Record<string, string>>({})

interface SelectedItem { outputPath: string; materialId?: number; selectedReason?: string; pendingReason?: string; sourceLabel?: string }
const selectedItems = computed<SelectedItem[]>(() => {
  const sel = taskStore.current?.resultJson?.selected
  const result = Array.isArray(sel) ? (sel as SelectedItem[]) : []
  const draft = taskStore.current?.resultJson?.draftResumeJson
  const pending: SelectedItem[] = []
  const encodePointerSegment = (segment: string) => segment.replace(/~/g, '~0').replace(/\//g, '~1')
  const visit = (value: unknown, path: string) => {
    if (!value || typeof value !== 'object') return
    if (Array.isArray(value)) {
      value.forEach((item, index) => visit(item, `${path}/${index}`))
      return
    }
    const record = value as Record<string, unknown>
    const isPending = record._pending === true || (typeof record._pending === 'object' && record._pending !== null)
    if ((record._source || Object.prototype.hasOwnProperty.call(record, '_pending')) && path) {
      pending.push({
        outputPath: path,
        pendingReason: isPending ? (typeof record._pending === 'object' ? String((record._pending as Record<string, unknown>).reason ?? '需要用户确认') : '需要用户确认') : undefined,
        sourceLabel: typeof record._source === 'string' ? record._source : undefined,
      })
    }
    Object.entries(record).filter(([key]) => !key.startsWith('_')).forEach(([key, child]) => visit(child, `${path}/${encodePointerSegment(key)}`))
  }
  visit(draft, '')
  return [...result, ...pending.filter((item) => !result.some((selected) => selected.outputPath === item.outputPath))]
})

const undecidedPending = computed(() => selectedItems.value.filter((item) => item.pendingReason && !decisions.value[item.outputPath]))
const canConfirm = computed(() => taskStore.current?.status === 'SUCCESS' && taskStore.current.confirmationStatus === 'PENDING')

onMounted(async () => {
  try {
    const taskId = Number(route.query.taskId)
    if (Number.isInteger(taskId) && taskId > 0) {
      await taskStore.load(taskId)
      if (taskStore.current?.status === 'PENDING' || taskStore.current?.status === 'RUNNING') taskStore.startPolling(taskId)
    } else {
      taskStore.restorePolling()
    }
  } catch {
    error.value = '任务状态无法获取，请检查网络或返回 JD 页面重新发起任务。'
  }
})

onBeforeUnmount(() => taskStore.stopPolling())

function setDecision(item: SelectedItem, decision: 'ACCEPT' | 'REJECT') {
  decisions.value[item.outputPath] = decision
}

function openEdit(item: SelectedItem) {
  editingPath.value = item.outputPath
  editedValue.value = ''
}

function saveEdit() {
  if (!editingPath.value || !editedValue.value.trim()) return
  decisions.value[editingPath.value] = 'EDIT'
  editedValues.value[editingPath.value] = editedValue.value.trim()
  editingPath.value = null
}

async function submitConfirmation() {
  const task = taskStore.current
  if (!task || !canConfirm.value) return
  if (undecidedPending.value.length) {
    error.value = '请先为每一项待确认内容选择接受、编辑或拒绝。'
    return
  }
  submitting.value = true
  error.value = ''
  try {
    const items = selectedItems.value.flatMap((item) => {
      const decision = decisions.value[item.outputPath]
      if (!decision) return []
      return [{ outputPath: item.outputPath, decision, ...(decision === 'EDIT' ? { editedValue: { value: editedValues.value[item.outputPath] } } : {}) }]
    })
    const response = await confirmTask(
      task.id,
      { taskUpdatedAt: task.updatedAt, items },
      `${task.id}-${Date.now()}`,
    )
    taskStore.clear()
    await router.push({ name: 'resume-list' })
  } catch {
    error.value = '提交失败，任务可能已被其他操作更新。已为你刷新最新状态。'
    try {
      await taskStore.load(task.id)
    } catch {
      error.value = '提交失败，且无法刷新任务状态。请检查网络后重试。'
    }
  } finally {
    submitting.value = false
  }
}

async function rejectDraft() {
  const task = taskStore.current
  if (!task || task.status !== 'SUCCESS' || task.confirmationStatus !== 'PENDING') return
  if (!window.confirm('放弃这份 AI 草稿？此操作不会创建简历版本。')) return

  rejecting.value = true
  error.value = ''
  try {
    await rejectTask(task.id, task.updatedAt)
    taskStore.clear()
    await router.push({ name: 'jobs' })
  } catch {
    error.value = '放弃草稿失败，任务可能已被其他操作更新。已为你刷新最新状态。'
    try {
      await taskStore.load(task.id)
    } catch {
      error.value = '放弃草稿失败，且无法刷新任务状态。请检查网络后重试。'
    }
  } finally {
    rejecting.value = false
  }
}
</script>

<template>
  <section class="workspace-page">
    <h1>逐项确认 · 岗位 #{{ props.jobId }}</h1>
    <p class="page-lead">草稿不会自动写入简历。每个待确认点必须由你明确处理。</p>
    <p v-if="taskStore.error || error" class="form-error" role="alert">{{ error || taskStore.error }}</p>
    <p v-if="!taskStore.current" class="empty-state">请先在 JD 页面发起生成任务。</p>
    <template v-else>
      <div class="task-status workspace-card"><strong>任务状态：{{ taskStore.current.status }}</strong><span>最近更新：{{ taskStore.current.updatedAt }}</span><RefreshCw v-if="taskStore.polling" :size="16" class="spinning" /></div>
      <p v-if="taskStore.current.status === 'FAILED'" class="form-error">{{ taskStore.current.errorMessage || '任务执行失败。' }}</p>
      <div v-if="canConfirm" class="confirmation-list">
        <article v-for="item in selectedItems" :key="item.outputPath" class="workspace-card confirmation-item">
          <div><code>{{ item.outputPath }}</code><p v-if="item.pendingReason" class="pending-note">待补充：{{ item.pendingReason }}</p><p v-else>来源：{{ item.sourceLabel || `资料 #${item.materialId ?? '—'}` }} · {{ item.selectedReason || '已选择用于生成' }}</p></div>
          <div class="item-actions">
            <button :class="{ selected: decisions[item.outputPath] === 'ACCEPT' }" title="接受" :disabled="submitting || rejecting" @click="setDecision(item, 'ACCEPT')"><Check :size="16" /></button>
            <button :class="{ selected: decisions[item.outputPath] === 'EDIT' }" title="编辑" :disabled="submitting || rejecting" @click="openEdit(item)"><Pencil :size="16" /></button>
            <button :class="{ selected: decisions[item.outputPath] === 'REJECT' }" title="拒绝" :disabled="submitting || rejecting" @click="setDecision(item, 'REJECT')"><X :size="16" /></button>
          </div>
        </article>
        <p v-if="!selectedItems.length" class="empty-state">任务未返回可确认项，请刷新后重试。</p>
        <div class="dialog-actions">
          <button class="btn-neon btn-primary" :disabled="submitting || rejecting || !!undecidedPending.length" @click="submitConfirmation">{{ submitting ? '正在保存版本…' : '提交已逐项确认的草稿' }}</button>
          <button class="danger-action" :disabled="submitting || rejecting" @click="rejectDraft">{{ rejecting ? '正在放弃草稿…' : '放弃草稿' }}</button>
        </div>
      </div>
      <p v-else-if="taskStore.current.status === 'SUCCESS' && taskStore.current.confirmationStatus === 'CONFIRMED'" class="empty-state">这份草稿已在其他操作中确认并生成简历版本。</p>
      <p v-else-if="taskStore.current.status === 'SUCCESS' && taskStore.current.confirmationStatus === 'REJECTED'" class="empty-state">这份草稿已被放弃，不会创建简历版本。</p>
      <dialog :open="editingPath !== null" class="edit-dialog">
        <form method="dialog" @submit.prevent="saveEdit"><h2>编辑 {{ editingPath }}</h2><textarea v-model="editedValue" required rows="5" /><div class="dialog-actions"><button class="btn-neon btn-ghost" type="button" :disabled="submitting || rejecting" @click="editingPath = null">取消</button><button class="btn-neon btn-primary" type="submit" :disabled="submitting || rejecting">保存编辑</button></div></form>
      </dialog>
    </template>
  </section>
</template>
