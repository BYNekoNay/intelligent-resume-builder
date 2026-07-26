<script setup lang="ts">
import { onMounted, onUnmounted, ref, computed, toRaw } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAiTaskStore } from '@/stores/aiTask'
import { getTask, confirmTask, rejectTask, retryTask } from '@/api/ai'
import { listResumesByJd, type ResumeSummary } from '@/api/resume'
import DraftContentFields from '@/components/DraftContentFields.vue'
import { Check, Pencil, Trash2 } from 'lucide-vue-next'

const route = useRoute()
const router = useRouter()
const taskStore = useAiTaskStore()

const loading = ref(true)
const error = ref('')
const confirming = ref(false)
const rejecting = ref(false)

// Task data
const task = ref<any>(null)
const resultJson = ref<any>(null)

// Draft sections for display
interface DraftItem {
  path: string
  section: string
  content: any
  provenance: Record<string, unknown>
  source: string | null
  pending: string | null
  decision: 'ACCEPT' | 'EDIT' | 'REJECT' | null
  editedValue: any
}

const draftItems = ref<DraftItem[]>([])
const selectedInfo = ref<any[]>([])
const unselectedInfo = ref<any[]>([])
const missingInfo = ref<any[]>([])
const warnings = ref<string[]>([])

// Same-JD dialog
const showJdDialog = ref(false)
const existingResumes = ref<ResumeSummary[]>([])
const resumeTitle = ref('')

// Edit dialog
const showEditDialog = ref(false)
const editingItem = ref<DraftItem | null>(null)
const editValue = ref<unknown>(null)

// Resume title input
const customTitle = ref('')

let pollTimer: ReturnType<typeof setTimeout> | null = null

onMounted(async () => {
  const tid = route.query.taskId
  if (!tid) {
    error.value = '缺少任务 ID'
    loading.value = false
    return
  }
  await loadTask(Number(tid))
})

onUnmounted(() => {
  if (pollTimer) clearTimeout(pollTimer)
})

async function loadTask(id: number) {
  loading.value = true
  error.value = ''
  try {
    const res = await getTask(id)
    task.value = res.data.data
    if (task.value.status === 'SUCCESS' && task.value.confirmationStatus === 'PENDING') {
      resultJson.value = task.value.resultJson
      parseDraft()
    } else if (task.value.status === 'PENDING' || task.value.status === 'RUNNING') {
      startPolling(id)
    } else if (task.value.status === 'FAILED') {
      error.value = task.value.errorMessage || 'AI 生成失败'
    }
  } catch (e: any) {
    error.value = e.response?.data?.message || '加载任务失败'
  } finally {
    loading.value = false
  }
}

function startPolling(id: number) {
  const poll = async () => {
    try {
      const res = await getTask(id)
      task.value = res.data.data
      if (task.value.status === 'SUCCESS') {
        resultJson.value = task.value.resultJson
        parseDraft()
        loading.value = false
        return
      }
      if (task.value.status === 'FAILED') {
        error.value = task.value.errorMessage || 'AI 生成失败'
        loading.value = false
        return
      }
      pollTimer = setTimeout(poll, 2000)
    } catch {
      pollTimer = setTimeout(poll, 3000)
    }
  }
  pollTimer = setTimeout(poll, 1500)
}

function parseDraft() {
  if (!resultJson.value) return
  const draft = resultJson.value.draftResumeJson
  selectedInfo.value = resultJson.value.selected ?? []
  unselectedInfo.value = resultJson.value.unselected ?? []
  missingInfo.value = resultJson.value.missing ?? []
  warnings.value = resultJson.value.warnings ?? []

  // Flatten draft into items by section
  const items: DraftItem[] = []
  const sections = ['basics', 'work', 'education', 'skills', 'projects', 'certificates']
  for (const section of sections) {
    const data = draft[section]
    if (!data) continue
    if (Array.isArray(data)) {
      data.forEach((entry: any, idx: number) => {
        const path = `${section}[${idx}]`
        items.push({
          path,
          section,
          content: stripMeta(entry),
          provenance: sourceMeta(entry),
          source: entry._source ?? (entry._sources ? '资料库' : null),
          pending: entry._pending?.reason ?? (typeof entry._pending === 'string' ? entry._pending : null),
          decision: entry._pending ? null : 'ACCEPT',
          editedValue: null,
        })
      })
    } else if (typeof data === 'object') {
      const path = section
      items.push({
        path,
        section,
        content: stripMeta(data),
        provenance: sourceMeta(data),
        source: data._source ?? (data._sources ? '资料库' : null),
        pending: data._pending?.reason ?? (typeof data._pending === 'string' ? data._pending : null),
        decision: data._pending ? null : 'ACCEPT',
        editedValue: null,
      })
    }
  }
  draftItems.value = items
}

function stripMeta(value: any): any {
  if (Array.isArray(value)) return value.map(stripMeta)
  if (value === null || typeof value !== 'object') return value
  return Object.fromEntries(
    Object.entries(value)
      .filter(([key]) => key !== '_source' && key !== '_sources' && key !== '_pending')
      .map(([key, nestedValue]) => [key, stripMeta(nestedValue)]),
  )
}

function sourceMeta(value: any): Record<string, unknown> {
  if (value?._source) return { _source: value._source }
  if (value?._sources) return { _sources: value._sources }
  if (value?._pending) return { _pending: value._pending }
  return {}
}

const SECTION_LABELS: Record<string, string> = {
  basics: '个人概要',
  work: '工作经历',
  education: '教育背景',
  skills: '技能',
  projects: '项目经历',
  certificates: '证书',
}

const SECTION_EDIT_TEMPLATES: Record<string, Record<string, unknown>> = {
  basics: { name: '', title: '', email: '', phone: '', location: '', summary: '' },
  work: { company: '', position: '', period: '', description: '', highlights: [] },
  education: { school: '', degree: '', major: '', period: '' },
  skills: { name: '', level: '' },
  projects: { name: '', role: '', period: '', description: '', highlights: [] },
  certificates: { name: '', issuer: '', date: '', credentialId: '' },
}

const groupedItems = computed(() => {
  const groups: Record<string, DraftItem[]> = {}
  for (const item of draftItems.value) {
    if (!groups[item.section]) groups[item.section] = []
    groups[item.section].push(item)
  }
  return groups
})

const pendingCount = computed(() =>
  draftItems.value.filter(i => i.decision === null).length
)

function openEdit(item: DraftItem) {
  editingItem.value = item
  const content = structuredClone(toRaw(item.content))
  editValue.value = content !== null && typeof content === 'object' && !Array.isArray(content)
    ? { ...(SECTION_EDIT_TEMPLATES[item.section] ?? {}), ...content }
    : content
  showEditDialog.value = true
}

function saveEdit() {
  if (!editingItem.value) return
  const cleanedValue = removeEmptyFields(editValue.value)
  editingItem.value.editedValue = Object.assign({}, cleanedValue as Record<string, unknown>, editingItem.value.provenance)
  editingItem.value.content = cleanedValue
  editingItem.value.decision = 'EDIT'
  showEditDialog.value = false
  editingItem.value = null
}

function removeEmptyFields(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(removeEmptyFields).filter(item => !isEmptyValue(item))
  }
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value)
        .map(([key, nestedValue]) => [key, removeEmptyFields(nestedValue)])
        .filter(([, nestedValue]) => !isEmptyValue(nestedValue)),
    )
  }
  return value
}

function isEmptyValue(value: unknown) {
  if (value === null || value === undefined || value === '') return true
  if (Array.isArray(value)) return value.length === 0
  return typeof value === 'object' && Object.keys(value).length === 0
}

function setDecision(item: DraftItem, decision: 'ACCEPT' | 'REJECT') {
  item.decision = decision
  if (decision === 'ACCEPT') item.editedValue = null
}

async function handleConfirm() {
  if (pendingCount.value > 0) {
    error.value = `还有 ${pendingCount.value} 项待处理（待补充信息需接受或拒绝）`
    return
  }

  // Check if same JD has existing resumes
  const jdId = extractJdId()
  if (jdId) {
    try {
      const res = await listResumesByJd(jdId)
      if (res.data.data && res.data.data.length > 0) {
        existingResumes.value = res.data.data
        showJdDialog.value = true
        return
      }
    } catch { /* ignore, proceed with new */ }
  }

  await doConfirm(null)
}

function extractJdId(): number | null {
  const jdId = task.value?.jobDescriptionId
  return typeof jdId === 'number' && jdId > 0 ? jdId : null
}

async function doConfirm(targetResumeId: number | null) {
  showJdDialog.value = false
  confirming.value = true
  error.value = ''

  try {
    const items = draftItems.value.map(item => ({
      outputPath: item.path,
      decision: item.decision ?? 'ACCEPT',
      ...(item.decision === 'EDIT' && item.editedValue ? { editedValue: item.editedValue } : {}),
    }))

    const idempotencyKey = `confirm-${task.value.id}-${Date.now()}`
    const res = await confirmTask(task.value.id, {
      taskUpdatedAt: task.value.updatedAt,
      items,
      resumeTitle: customTitle.value || undefined,
      targetResumeId,
    }, idempotencyKey)

    const data = res.data.data
    taskStore.clear()
    // Navigate to resume detail
    router.push(`/resumes/${data.resumeId}`)
  } catch (e: any) {
    error.value = e.response?.data?.message || '确认失败'
  } finally {
    confirming.value = false
  }
}

async function handleReject() {
  if (!window.confirm('确定拒绝此草稿？拒绝后不会创建简历。')) return
  rejecting.value = true
  try {
    await rejectTask(task.value.id, task.value.updatedAt)
    taskStore.clear()
    router.push('/generate')
  } catch (e: any) {
    error.value = e.response?.data?.message || '操作失败'
  } finally {
    rejecting.value = false
  }
}

async function handleRetry() {
  error.value = ''
  try {
    await retryTask(task.value.id)
    task.value.status = 'PENDING'
    loading.value = true
    startPolling(task.value.id)
  } catch (e: any) {
    error.value = e.response?.data?.message || '重试失败'
  }
}
</script>

<template>
  <div class="confirm-page">
    <header>
      <h1>确认 AI 草稿</h1>
      <p class="subtitle">逐项审核生成内容，确认后将创建岗位简历</p>
    </header>

    <!-- Loading / Polling -->
    <div v-if="loading" class="status-card">
      <div class="spinner-lg"></div>
      <p>AI 正在生成你的岗位简历...</p>
      <p class="hint">通常需要 10-30 秒</p>
    </div>

    <!-- Error / Failed -->
    <div v-else-if="error && (!task || task.status === 'FAILED')" class="status-card error">
      <p class="error-text">{{ error }}</p>
      <button class="btn-primary" @click="handleRetry">重试生成</button>
    </div>

    <!-- Draft confirmation -->
    <div v-else-if="task && task.status === 'SUCCESS'" class="draft-container">
      <!-- Warnings -->
      <div v-if="warnings.length" class="warnings">
        <p v-for="w in warnings" :key="w" class="warning-item">{{ w }}</p>
      </div>

      <!-- Missing info -->
      <div v-if="missingInfo.length" class="missing-section">
        <h3>缺失信息（JD 要求但资料库未覆盖）</h3>
        <ul>
          <li v-for="(m, i) in missingInfo" :key="i">
            <strong>{{ m.section }}</strong>：{{ m.reason }}
          </li>
        </ul>
      </div>

      <!-- Draft items by section -->
      <div v-for="(items, section) in groupedItems" :key="section" class="draft-section">
        <h3>{{ SECTION_LABELS[section as string] ?? section }}</h3>
        <div v-for="(item, itemIndex) in items" :key="item.path" :class="['draft-item', item.decision?.toLowerCase()]">
          <div v-if="items.length > 1 || item.source || item.pending" class="item-header">
            <span v-if="items.length > 1" class="item-number">第 {{ itemIndex + 1 }} 条</span>
            <span v-if="item.source" class="source-badge">来源：资料库</span>
            <span v-if="item.pending" class="pending-badge">待补充：{{ item.pending }}</span>
          </div>
          <DraftContentFields :model-value="item.content" />
          <div class="item-actions">
            <button
              :class="['action-btn accept', { active: item.decision === 'ACCEPT' }]"
              :aria-pressed="item.decision === 'ACCEPT'"
              @click="setDecision(item, 'ACCEPT')"
            ><Check :size="15" /><span>接受</span></button>
            <button class="action-btn edit" @click="openEdit(item)"><Pencil :size="15" /><span>编辑</span></button>
            <button
              :class="['action-btn reject', { active: item.decision === 'REJECT' }]"
              :aria-pressed="item.decision === 'REJECT'"
              @click="setDecision(item, 'REJECT')"
            ><Trash2 :size="15" /><span>删除</span></button>
          </div>
        </div>
      </div>

      <!-- Unselected materials -->
      <details v-if="unselectedInfo.length" class="unselected-details">
        <summary>未使用的资料（{{ unselectedInfo.length }} 条）</summary>
        <ul>
          <li v-for="(u, i) in unselectedInfo" :key="i">
            {{ u.title || '未命名资料' }}：{{ u.unselectedReason }}
          </li>
        </ul>
      </details>

      <!-- Resume title -->
      <div class="title-input">
        <label>简历名称（可选，默认使用"公司 - 岗位"）</label>
        <input v-model="customTitle" placeholder="例如：字节跳动 - Java 后端工程师" class="input" />
      </div>

      <!-- Error -->
      <p v-if="error" class="error-msg">{{ error }}</p>

      <!-- Actions -->
      <div class="confirm-actions">
        <button class="btn-secondary" @click="handleReject" :disabled="rejecting">
          拒绝草稿
        </button>
        <button class="btn-primary" @click="handleConfirm" :disabled="confirming || pendingCount > 0">
          <span v-if="confirming" class="spinner"></span>
          {{ confirming ? '创建中...' : `确认并创建简历${pendingCount > 0 ? `（${pendingCount} 项待处理）` : ''}` }}
        </button>
      </div>
    </div>

    <!-- Same-JD Dialog -->
    <Teleport to="body">
      <div v-if="showJdDialog" class="dialog-overlay" @click.self="showJdDialog = false">
        <div class="dialog">
          <h3>该岗位已有简历</h3>
          <p>检测到相同岗位已存在以下简历，请选择操作：</p>
          <div class="existing-list">
            <div v-for="r in existingResumes" :key="r.id" class="existing-item">
              <span>{{ r.title }}</span>
              <button class="btn-small" @click="doConfirm(r.id)">更新此简历</button>
            </div>
          </div>
          <div class="dialog-actions">
            <button class="btn-primary" @click="doConfirm(null)">新建一份简历</button>
            <button class="btn-secondary" @click="showJdDialog = false">取消</button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- Edit Dialog -->
    <Teleport to="body">
      <div v-if="showEditDialog" class="dialog-overlay" @click.self="showEditDialog = false">
        <div class="dialog edit-dialog" role="dialog" aria-modal="true" aria-labelledby="draft-edit-title">
          <h3 id="draft-edit-title">编辑内容</h3>
          <DraftContentFields v-model="editValue" editable />
          <div class="dialog-actions">
            <button class="btn-primary" @click="saveEdit">保存</button>
            <button class="btn-secondary" @click="showEditDialog = false">取消</button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.confirm-page {
  max-width: 760px;
  margin: 0 auto;
  padding: 2rem 1rem;
}
header h1 {
  font-size: 1.5rem;
  font-weight: 700;
}
.subtitle {
  color: #6b7280;
  margin-bottom: 1.5rem;
}
.status-card {
  text-align: center;
  padding: 3rem;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
}
.status-card.error {
  border-color: #fca5a5;
}
.error-text {
  color: #dc2626;
  margin-bottom: 1rem;
}
.spinner-lg {
  width: 32px;
  height: 32px;
  border: 3px solid #e5e7eb;
  border-top-color: #0e7490;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
  margin: 0 auto 1rem;
}
.hint {
  color: #9ca3af;
  font-size: 0.85rem;
}
.warnings {
  background: #fffbeb;
  border: 1px solid #fde68a;
  border-radius: 8px;
  padding: 0.75rem 1rem;
  margin-bottom: 1rem;
}
.warning-item {
  font-size: 0.85rem;
  color: #92400e;
}
.missing-section {
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 8px;
  padding: 0.75rem 1rem;
  margin-bottom: 1.5rem;
}
.missing-section h3 {
  font-size: 0.9rem;
  color: #991b1b;
  margin-bottom: 0.5rem;
}
.missing-section li {
  font-size: 0.85rem;
  color: #7f1d1d;
}
.draft-section {
  margin-bottom: 1.5rem;
}
.draft-section h3 {
  font-size: 1rem;
  font-weight: 600;
  margin-bottom: 0.5rem;
  padding-bottom: 0.25rem;
  border-bottom: 1px solid #e5e7eb;
}
.draft-item {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 0.75rem;
  margin-bottom: 0.5rem;
  transition: border-color 0.15s;
}
.draft-item.accept {
  border-color: #a7f3d0;
}
.draft-item.reject {
  border-color: #fca5a5;
  opacity: 0.6;
}
.draft-item.edit {
  border-color: #93c5fd;
}
.item-header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.4rem;
  flex-wrap: wrap;
}
.item-number {
  font-size: 0.75rem;
  color: #64748b;
  font-weight: 600;
}
.source-badge {
  font-size: 0.7rem;
  padding: 0.1rem 0.4rem;
  background: #dbeafe;
  color: #1e40af;
  border-radius: 3px;
}
.pending-badge {
  font-size: 0.7rem;
  padding: 0.1rem 0.4rem;
  background: #fef3c7;
  color: #92400e;
  border-radius: 3px;
}
.item-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 14px;
  padding-top: 10px;
  border-top: 1px solid #edf1f5;
}
.action-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-width: 76px;
  height: 34px;
  padding: 0 12px;
  border: 1px solid #d9e0e8;
  border-radius: 6px;
  background: #fff;
  color: #475569;
  font: 600 13px/1 inherit;
  white-space: nowrap;
  cursor: pointer;
  transition: border-color 0.15s ease, background-color 0.15s ease, color 0.15s ease, transform 0.15s ease;
}
.action-btn svg {
  flex: 0 0 auto;
}
.action-btn:hover {
  border-color: #94a3b8;
  background: #f8fafc;
  transform: translateY(-1px);
}
.action-btn:focus-visible {
  outline: 2px solid rgba(14, 116, 144, 0.28);
  outline-offset: 2px;
}
.action-btn.accept {
  color: #047857;
}
.action-btn.accept:hover {
  border-color: #6ee7b7;
  background: #ecfdf5;
}
.action-btn.accept.active {
  background: #059669;
  border-color: #059669;
  color: #fff;
}
.action-btn.edit {
  color: #1d4ed8;
}
.action-btn.reject.active {
  background: #dc2626;
  border-color: #dc2626;
  color: #fff;
}
.action-btn.reject {
  color: #b91c1c;
}
.action-btn.reject:hover {
  border-color: #fca5a5;
  background: #fef2f2;
}
.unselected-details {
  margin-bottom: 1.5rem;
  font-size: 0.85rem;
  color: #6b7280;
}
.unselected-details summary {
  cursor: pointer;
  font-weight: 500;
}
.title-input {
  margin-bottom: 1.5rem;
}
.title-input label {
  display: block;
  font-size: 0.85rem;
  color: #6b7280;
  margin-bottom: 0.4rem;
}
.input {
  width: 100%;
  padding: 0.5rem 0.75rem;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font-size: 0.9rem;
}
.error-msg {
  color: #dc2626;
  font-size: 0.85rem;
  margin-bottom: 1rem;
}
.confirm-actions {
  display: flex;
  gap: 0.75rem;
  justify-content: flex-end;
}
.btn-primary {
  padding: 0.6rem 1.5rem;
  background: #0e7490;
  color: #fff;
  border: none;
  border-radius: 6px;
  font-size: 0.9rem;
  cursor: pointer;
  font-weight: 500;
  display: flex;
  align-items: center;
  gap: 0.4rem;
}
.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.btn-secondary {
  padding: 0.6rem 1.5rem;
  background: #fff;
  color: #374151;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font-size: 0.9rem;
  cursor: pointer;
}
.btn-small {
  font-size: 0.75rem;
  padding: 0.2rem 0.5rem;
  background: #0e7490;
  color: #fff;
  border: none;
  border-radius: 4px;
  cursor: pointer;
}
.spinner {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255,255,255,0.3);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
.dialog-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}
.dialog {
  background: #fff;
  border-radius: 12px;
  padding: 1.5rem;
  max-width: 480px;
  width: 90%;
  max-height: 80vh;
  overflow-y: auto;
}
.dialog h3 {
  margin-bottom: 0.75rem;
}
.edit-dialog {
  max-width: 640px;
}
.existing-list {
  margin: 1rem 0;
}
.existing-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.5rem 0;
  border-bottom: 1px solid #f3f4f6;
}
.dialog-actions {
  display: flex;
  gap: 0.75rem;
  margin-top: 1rem;
}
@media (max-width: 560px) {
  .item-actions {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
  .action-btn {
    width: 100%;
    min-width: 0;
    padding: 0 8px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .action-btn {
    transition: none;
  }
  .action-btn:hover {
    transform: none;
  }
}
</style>
