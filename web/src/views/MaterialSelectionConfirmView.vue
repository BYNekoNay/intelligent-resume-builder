<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { AlertTriangle, Check, Plus, RefreshCw, Sparkles, Trash2 } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import {
  confirmMaterials,
  getTask,
  retryTask,
  type AiTask,
  type MaterialSelectionItem,
  type MaterialSelectionResult,
} from '@/api/ai'
import { useAiTaskStore } from '@/stores/aiTask'

const route = useRoute()
const router = useRouter()
const taskStore = useAiTaskStore()
const task = ref<AiTask | null>(null)
const loading = ref(true)
const confirming = ref(false)
const error = ref('')
const selectedIds = ref(new Set<number>())
const forcedIds = ref(new Set<number>())
let timer: number | null = null

const taskId = computed(() => Number(route.query.taskId))
const result = computed<MaterialSelectionResult>(() => {
  const raw = task.value?.resultJson ?? {}
  const list = (key: string) => Array.isArray(raw[key]) ? raw[key] as MaterialSelectionItem[] : []
  return {
    recommended: list('recommended'),
    unselected: list('unselected'),
    excluded: list('excluded'),
    missingRequirements: Array.isArray(raw.missingRequirements)
      ? raw.missingRequirements.filter((item): item is string => typeof item === 'string')
      : [],
  }
})
const allItems = computed(() => [...result.value.recommended, ...result.value.unselected, ...result.value.excluded])
const selectedItems = computed(() => allItems.value.filter(item => selectedIds.value.has(item.materialId)))
const unselectedItems = computed(() => [...result.value.recommended, ...result.value.unselected]
  .filter(item => !selectedIds.value.has(item.materialId)))
const excludedItems = computed(() => result.value.excluded
  .filter(item => item.exclusionReason === 'GLOBAL' && !selectedIds.value.has(item.materialId)))
const manuallyExcludedItems = computed(() => result.value.excluded
  .filter(item => item.exclusionReason === 'MANUAL'))

onMounted(async () => {
  if (!Number.isInteger(taskId.value) || taskId.value <= 0) {
    loading.value = false
    error.value = '选材任务参数无效，请返回生成工作台重新开始。'
    return
  }
  await loadTask(true)
})

onBeforeUnmount(() => {
  if (timer !== null) window.clearTimeout(timer)
})

async function loadTask(initialize = false) {
  try {
    task.value = (await getTask(taskId.value)).data.data
    if (task.value.taskType !== 'JOB_MATERIAL_SELECTION') {
      error.value = '该任务不是岗位选材任务。'
      loading.value = false
      return
    }
    if (task.value.status === 'SUCCESS') {
      if (initialize) selectedIds.value = new Set(result.value.recommended.map(item => item.materialId))
      loading.value = false
      return
    }
    if (task.value.status === 'FAILED' || task.value.status === 'CANCELLED') {
      error.value = task.value.errorMessage || 'AI 选材失败，请重试。'
      loading.value = false
      return
    }
    timer = window.setTimeout(() => void loadTask(initialize), 1500)
  } catch (e: any) {
    error.value = e.response?.data?.message || '暂时无法读取选材结果，请检查网络后重试。'
    loading.value = false
  }
}

function add(item: MaterialSelectionItem, force = false) {
  if (selectedIds.value.size >= 30) {
    error.value = '一次最多确认 30 条资料，请先移除不必要的资料。'
    return
  }
  selectedIds.value = new Set(selectedIds.value).add(item.materialId)
  if (force) forcedIds.value = new Set(forcedIds.value).add(item.materialId)
  error.value = ''
}

function remove(item: MaterialSelectionItem) {
  const nextSelected = new Set(selectedIds.value)
  nextSelected.delete(item.materialId)
  selectedIds.value = nextSelected
  const nextForced = new Set(forcedIds.value)
  nextForced.delete(item.materialId)
  forcedIds.value = nextForced
}

async function confirmSelection() {
  if (!task.value || selectedIds.value.size === 0) {
    error.value = '请至少选择一条真实资料后再生成简历。'
    return
  }
  confirming.value = true
  error.value = ''
  try {
    const response = await confirmMaterials(task.value.id, {
      taskUpdatedAt: task.value.updatedAt,
      selectedMaterialIds: [...selectedIds.value],
      forcedIncludedMaterialIds: [...forcedIds.value],
      resumeTitle: typeof task.value.resultJson?.resumeTitle === 'string' ? task.value.resultJson.resumeTitle : undefined,
    }, `confirm-materials-${task.value.id}-${Date.now()}`)
    const generationTask = response.data.data
    taskStore.remember(generationTask.id)
    await router.push(`/generate/confirm?taskId=${generationTask.id}`)
  } catch (e: any) {
    error.value = e.response?.data?.message || '无法确认选材，请检查资料后重试。'
  } finally {
    confirming.value = false
  }
}

async function retry() {
  if (!task.value) return
  error.value = ''
  loading.value = true
  try {
    await retryTask(task.value.id)
    await loadTask(true)
  } catch (e: any) {
    loading.value = false
    error.value = e.response?.data?.message || '无法重试选材。'
  }
}

function typeLabel(type: string) {
  return ({
    WORK_EXPERIENCE: '工作经历', PROJECT_EXPERIENCE: '项目经历', EDUCATION: '教育背景',
    SKILL: '技能', CERTIFICATE: '证书', AWARD: '荣誉', HIGHLIGHT: '亮点',
    ACHIEVEMENT: '量化成果', LEADERSHIP_EXPERIENCE: '管理 / 协作', SKILL_EVIDENCE: '技能证据',
  } as Record<string, string>)[type] ?? '职业资料'
}
</script>

<template>
  <main class="selection-page">
    <header class="selection-header">
      <p class="eyebrow"><Sparkles :size="14" /> AI 选材</p>
      <h1>确认用于这份简历的资料</h1>
      <p>AI 已根据岗位要求整理候选，你可以在生成前添加或移除资料。</p>
    </header>

    <section v-if="loading" class="status-panel" aria-live="polite">
      <span class="spinner"></span>
      <h2>正在理解岗位并筛选资料</h2>
      <p>系统只会从你的资料库中选择，不会补造经历。</p>
    </section>

    <section v-else-if="error && (!task || task.status !== 'SUCCESS')" class="status-panel error-panel">
      <AlertTriangle :size="24" />
      <p>{{ error }}</p>
      <button v-if="task?.status === 'FAILED'" class="btn-primary" @click="retry"><RefreshCw :size="15" />重新选材</button>
      <router-link v-else class="btn-secondary" to="/generate">返回生成工作台</router-link>
    </section>

    <template v-else-if="task?.status === 'SUCCESS'">
      <section class="selection-section selected-section">
        <div class="section-heading">
          <div><h2>将用于生成</h2><p>生成模型只会收到这里确认的资料。</p></div>
          <span class="count">{{ selectedItems.length }} / 30</span>
        </div>
        <p v-if="selectedItems.length === 0" class="empty-hint">尚未选择资料，请从下方候选中添加。</p>
        <article v-for="item in selectedItems" :key="item.materialId" class="material-row selected">
          <div class="material-copy">
            <div class="material-title"><span>{{ typeLabel(item.materialType) }}</span><strong>{{ item.title || '未命名资料' }}</strong></div>
            <p v-if="item.reason">{{ item.reason }}</p>
            <ul v-if="item.matchedRequirements?.length" class="requirement-list">
              <li v-for="requirement in item.matchedRequirements" :key="requirement">{{ requirement }}</li>
            </ul>
          </div>
          <button class="icon-action remove" title="从本次生成中移除" @click="remove(item)"><Trash2 :size="17" /><span>移除</span></button>
        </article>
      </section>

      <section v-if="unselectedItems.length" class="selection-section">
        <div class="section-heading"><div><h2>其他候选</h2><p>相关度较低或岗位篇幅有限，默认不使用。</p></div></div>
        <article v-for="item in unselectedItems" :key="item.materialId" class="material-row">
          <div class="material-copy">
            <div class="material-title"><span>{{ typeLabel(item.materialType) }}</span><strong>{{ item.title || '未命名资料' }}</strong></div>
            <p>{{ item.reason || '与当前岗位的直接关联较弱。' }}</p>
          </div>
          <button class="icon-action add" @click="add(item)"><Plus :size="17" /><span>加入</span></button>
        </article>
      </section>

      <section v-if="excludedItems.length" class="selection-section excluded-section">
        <div class="section-heading"><div><h2>默认排除的资料</h2><p>这些资料按你的全局偏好不参与生成，只有明确加入才会使用。</p></div></div>
        <article v-for="item in excludedItems" :key="item.materialId" class="material-row">
          <div class="material-copy">
            <div class="material-title"><span>{{ typeLabel(item.materialType) }}</span><strong>{{ item.title || '未命名资料' }}</strong></div>
            <p>{{ item.reason || '你已在资料库中设置为默认不使用。' }}</p>
          </div>
          <button class="icon-action force" @click="add(item, true)"><Plus :size="17" /><span>本次强制加入</span></button>
        </article>
      </section>

      <section v-if="manuallyExcludedItems.length" class="selection-section excluded-section">
        <div class="section-heading"><div><h2>本次已排除的资料</h2><p>这些资料已明确排除，不能在本次生成中重新加入。</p></div></div>
        <article v-for="item in manuallyExcludedItems" :key="item.materialId" class="material-row">
          <div class="material-copy">
            <div class="material-title"><span>{{ typeLabel(item.materialType) }}</span><strong>{{ item.title || '未命名资料' }}</strong></div>
            <p>{{ item.reason || '已在生成工作台中排除。' }}</p>
          </div>
        </article>
      </section>

      <section v-if="result.missingRequirements.length" class="gap-band">
        <div><AlertTriangle :size="18" /><h2>尚未覆盖的岗位要求</h2></div>
        <ul><li v-for="gap in result.missingRequirements" :key="gap">{{ gap }}</li></ul>
      </section>

      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      <footer class="page-actions">
        <router-link class="btn-secondary" to="/generate">重新配置</router-link>
        <button class="btn-primary" :disabled="confirming || selectedItems.length === 0" @click="confirmSelection">
          <Check :size="16" />{{ confirming ? '正在创建草稿...' : '确认选材并生成简历' }}
        </button>
      </footer>
    </template>
  </main>
</template>

<style scoped>
.selection-page { max-width: 840px; margin: 0 auto; padding: 2rem 1rem 3rem; }
.selection-header { margin-bottom: 1.75rem; }
.selection-header h1 { margin: 0.35rem 0; font-size: 1.6rem; }
.selection-header > p:last-child, .section-heading p, .material-copy p { color: #64748b; }
.selection-section { padding: 1.2rem 0; border-top: 1px solid #e2e8f0; }
.selected-section { border-top: 2px solid #0e7490; }
.section-heading { display: flex; justify-content: space-between; gap: 1rem; align-items: start; margin-bottom: 0.8rem; }
.section-heading h2, .gap-band h2, .status-panel h2 { margin: 0; font-size: 1rem; }
.section-heading p { margin: 0.25rem 0 0; font-size: 0.84rem; }
.count { color: #0e7490; font-weight: 700; }
.material-row { display: flex; align-items: center; justify-content: space-between; gap: 1rem; min-height: 76px; padding: 0.85rem 0; border-top: 1px solid #f1f5f9; }
.material-row.selected { border-left: 3px solid #10b981; padding-left: 0.8rem; }
.material-copy { min-width: 0; }
.material-title { display: flex; align-items: center; gap: 0.55rem; }
.material-title span { color: #0e7490; font-size: 0.72rem; font-weight: 700; }
.material-title strong { overflow-wrap: anywhere; }
.material-copy p { margin: 0.35rem 0 0; font-size: 0.84rem; }
.requirement-list { display: flex; flex-wrap: wrap; gap: 0.35rem; margin: 0.45rem 0 0; padding: 0; list-style: none; }
.requirement-list li { padding: 0.15rem 0.4rem; border-radius: 3px; background: #ecfeff; color: #155e75; font-size: 0.72rem; }
.icon-action, .btn-primary, .btn-secondary { display: inline-flex; align-items: center; justify-content: center; gap: 0.4rem; min-height: 36px; border-radius: 6px; white-space: nowrap; cursor: pointer; text-decoration: none; }
.icon-action { flex: 0 0 auto; padding: 0 0.7rem; background: #fff; border: 1px solid #cbd5e1; color: #334155; }
.icon-action.add { color: #047857; border-color: #a7f3d0; }
.icon-action.remove { color: #b91c1c; border-color: #fecaca; }
.icon-action.force { color: #9a3412; border-color: #fdba74; }
.gap-band { margin: 1rem 0; padding: 1rem; border-left: 3px solid #f59e0b; background: #fffbeb; }
.gap-band > div { display: flex; align-items: center; gap: 0.5rem; color: #92400e; }
.gap-band ul { margin: 0.65rem 0 0; color: #78350f; }
.status-panel { display: grid; justify-items: center; gap: 0.8rem; padding: 3rem 1rem; text-align: center; border-block: 1px solid #e2e8f0; }
.error-panel { color: #b91c1c; }
.spinner { width: 28px; height: 28px; border: 3px solid #cbd5e1; border-top-color: #0e7490; border-radius: 50%; animation: spin .75s linear infinite; }
.inline-error { color: #b91c1c; }
.page-actions { display: flex; justify-content: flex-end; gap: 0.75rem; padding-top: 1rem; border-top: 1px solid #e2e8f0; }
.btn-primary { padding: 0 1rem; border: 1px solid #0e7490; background: #0e7490; color: #fff; }
.btn-secondary { padding: 0 1rem; border: 1px solid #cbd5e1; background: #fff; color: #334155; }
.btn-primary:disabled { opacity: .5; cursor: not-allowed; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 600px) {
  .material-row { align-items: stretch; flex-direction: column; }
  .icon-action { width: 100%; }
  .page-actions { display: grid; grid-template-columns: 1fr; }
}
@media (prefers-reduced-motion: reduce) { .spinner { animation-duration: 1.8s; } }
</style>
