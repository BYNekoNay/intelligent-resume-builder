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
import { useLocale } from '@/i18n'

const { t } = useLocale()
const route = useRoute()
const router = useRouter()
const taskStore = useAiTaskStore()
const MATERIAL_TYPE_KEYS: Record<string, string> = {
  WORK_EXPERIENCE: 'materialSelection.typeWorkExperience',
  PROJECT_EXPERIENCE: 'materialSelection.typeProjectExperience',
  EDUCATION: 'materialSelection.typeEducation',
  SKILL: 'materialSelection.typeSkill',
  CERTIFICATE: 'materialSelection.typeCertificate',
  AWARD: 'materialSelection.typeAward',
  HIGHLIGHT: 'materialSelection.typeHighlight',
  ACHIEVEMENT: 'materialSelection.typeAchievement',
  LEADERSHIP_EXPERIENCE: 'materialSelection.typeLeadership',
  SKILL_EVIDENCE: 'materialSelection.typeSkillEvidence',
}
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
    error.value = t('materialSelection.errorInvalidTask')
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
      error.value = t('materialSelection.errorWrongTaskType')
      loading.value = false
      return
    }
    if (task.value.status === 'SUCCESS') {
      if (initialize) selectedIds.value = new Set(result.value.recommended.map(item => item.materialId))
      loading.value = false
      return
    }
    if (task.value.status === 'FAILED' || task.value.status === 'CANCELLED') {
      error.value = task.value.errorMessage || t('materialSelection.errorAiFailed')
      loading.value = false
      return
    }
    timer = window.setTimeout(() => void loadTask(initialize), 1500)
  } catch (e: any) {
    error.value = e.response?.data?.message || t('materialSelection.errorLoadFailed')
    loading.value = false
  }
}

function add(item: MaterialSelectionItem, force = false) {
  if (selectedIds.value.size >= 30) {
    error.value = t('materialSelection.errorMaxLimit')
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
    error.value = t('materialSelection.errorNoSelection')
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
    error.value = e.response?.data?.message || t('materialSelection.errorConfirmFailed')
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
    error.value = e.response?.data?.message || t('materialSelection.errorRetryFailed')
  }
}

function typeLabel(type: string) {
  return t(MATERIAL_TYPE_KEYS[type] ?? 'materialSelection.typeDefault')
}
</script>

<template>
  <main class="selection-page">
    <header class="selection-header">
      <p class="eyebrow"><Sparkles :size="14" /> {{ t('materialSelection.eyebrow') }}</p>
      <h1>{{ t('materialSelection.title') }}</h1>
      <p>{{ t('materialSelection.subtitle') }}</p>
      <div class="selection-route" aria-hidden="true"><span class="done"><Check :size="12" />{{ t('generationWorkbench.stepTargetJob') }}</span><i></i><span class="active">{{ t('generationWorkbench.stepMaterialScope') }}</span><i></i><span>{{ t('generationWorkbench.stepAiSelection') }}</span></div>
    </header>

    <section v-if="loading" class="status-panel" aria-live="polite">
      <span class="spinner"></span>
      <h2>{{ t('materialSelection.loadingTitle') }}</h2>
      <p>{{ t('materialSelection.loadingDesc') }}</p>
    </section>

    <section v-else-if="error && (!task || task.status !== 'SUCCESS')" class="status-panel error-panel">
      <AlertTriangle :size="24" />
      <p>{{ error }}</p>
      <button v-if="task?.status === 'FAILED'" class="btn-primary" @click="retry"><RefreshCw :size="15" />{{ t('materialSelection.retrySelection') }}</button>
      <router-link v-else class="btn-secondary" to="/generate">{{ t('materialSelection.backToWorkspace') }}</router-link>
    </section>

    <template v-else-if="task?.status === 'SUCCESS'">
      <section class="selection-section selected-section">
        <div class="section-heading">
          <div><h2>{{ t('materialSelection.selectedTitle') }}</h2><p>{{ t('materialSelection.selectedDesc') }}</p></div>
          <span class="count">{{ selectedItems.length }} / 30</span>
        </div>
        <p v-if="selectedItems.length === 0" class="empty-hint">{{ t('materialSelection.emptyHint') }}</p>
        <article v-for="item in selectedItems" :key="item.materialId" class="material-row selected">
          <div class="material-copy">
            <div class="material-title"><span>{{ typeLabel(item.materialType) }}</span><strong>{{ item.title || t('materialSelection.unnamedMaterial') }}</strong></div>
            <p v-if="item.reason">{{ item.reason }}</p>
            <ul v-if="item.matchedRequirements?.length" class="requirement-list">
              <li v-for="requirement in item.matchedRequirements" :key="requirement">{{ requirement }}</li>
            </ul>
          </div>
          <button class="icon-action remove" :title="t('materialSelection.removeTitle')" @click="remove(item)"><Trash2 :size="17" /><span>{{ t('materialSelection.remove') }}</span></button>
        </article>
      </section>

      <section v-if="unselectedItems.length" class="selection-section">
        <div class="section-heading"><div><h2>{{ t('materialSelection.otherCandidates') }}</h2><p>{{ t('materialSelection.otherCandidatesDesc') }}</p></div></div>
        <article v-for="item in unselectedItems" :key="item.materialId" class="material-row">
          <div class="material-copy">
            <div class="material-title"><span>{{ typeLabel(item.materialType) }}</span><strong>{{ item.title || t('materialSelection.unnamedMaterial') }}</strong></div>
            <p>{{ item.reason || t('materialSelection.weakRelevance') }}</p>
          </div>
          <button class="icon-action add" @click="add(item)"><Plus :size="17" /><span>{{ t('materialSelection.add') }}</span></button>
        </article>
      </section>

      <section v-if="excludedItems.length" class="selection-section excluded-section">
        <div class="section-heading"><div><h2>{{ t('materialSelection.defaultExcluded') }}</h2><p>{{ t('materialSelection.defaultExcludedDesc') }}</p></div></div>
        <article v-for="item in excludedItems" :key="item.materialId" class="material-row">
          <div class="material-copy">
            <div class="material-title"><span>{{ typeLabel(item.materialType) }}</span><strong>{{ item.title || t('materialSelection.unnamedMaterial') }}</strong></div>
            <p>{{ item.reason || t('materialSelection.globallyExcluded') }}</p>
          </div>
          <button class="icon-action force" @click="add(item, true)"><Plus :size="17" /><span>{{ t('materialSelection.forceAddThisTime') }}</span></button>
        </article>
      </section>

      <section v-if="manuallyExcludedItems.length" class="selection-section excluded-section">
        <div class="section-heading"><div><h2>{{ t('materialSelection.excludedThisTime') }}</h2><p>{{ t('materialSelection.excludedThisTimeDesc') }}</p></div></div>
        <article v-for="item in manuallyExcludedItems" :key="item.materialId" class="material-row">
          <div class="material-copy">
            <div class="material-title"><span>{{ typeLabel(item.materialType) }}</span><strong>{{ item.title || t('materialSelection.unnamedMaterial') }}</strong></div>
            <p>{{ item.reason || t('materialSelection.excludedInWorkbench') }}</p>
          </div>
        </article>
      </section>

      <section v-if="result.missingRequirements.length" class="gap-band">
        <div><AlertTriangle :size="18" /><h2>{{ t('materialSelection.uncoveredTitle') }}</h2></div>
        <ul><li v-for="gap in result.missingRequirements" :key="gap">{{ gap }}</li></ul>
      </section>

      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      <footer class="page-actions">
        <router-link class="btn-secondary" to="/generate">{{ t('materialSelection.reconfigure') }}</router-link>
        <button class="btn-primary" :disabled="confirming || selectedItems.length === 0" @click="confirmSelection">
          <Check :size="16" />{{ confirming ? t('materialSelection.creatingDraft') : t('materialSelection.confirmAndGenerate') }}
        </button>
      </footer>
    </template>
  </main>
</template>

<style scoped>
.selection-page { display: grid; gap: 24px; width: min(100%, 920px); margin: 0 auto; padding: 8px 0 48px; }
.selection-header { display: grid; gap: 0; padding-bottom: 22px; border-bottom: 1px solid var(--border); }
.selection-header .eyebrow { justify-self: start; }
.selection-header h1 { margin: 5px 0 7px; color: var(--text-primary); font-family: var(--font-display); font-size: 34px; letter-spacing: 0; }
.selection-header > p:not(.eyebrow) { max-width: 680px; margin: 0; color: var(--text-secondary); font-size: 12px; line-height: 1.65; }
.selection-route { display: grid; grid-template-columns: auto 34px auto 34px auto; align-items: center; justify-content: end; gap: 7px; margin-top: 20px; color: var(--text-tertiary); font-family: var(--font-utility); font-size: 9px; font-weight: 700; }
.selection-route span { display: inline-flex; align-items: center; gap: 4px; }
.selection-route i { height: 1px; background: var(--border); }
.selection-route .done { color: var(--text-primary); }
.selection-route .active { color: var(--accent); }
.selection-section { display: grid; gap: 0; padding: 22px 24px; border: 1px solid var(--border); border-radius: 7px; background: var(--bg-surface); box-shadow: var(--shadow-sm); }
.selected-section { border-left: 4px solid var(--accent); }
.excluded-section { background: color-mix(in srgb, var(--bg-page) 55%, var(--bg-surface)); }
.section-heading { display: flex; align-items: start; justify-content: space-between; gap: 16px; margin-bottom: 10px; padding-bottom: 15px; border-bottom: 1px solid var(--border-soft); }
.section-heading h2, .gap-band h2, .status-panel h2 { margin: 0; color: var(--text-primary); font-size: 15px; }
.section-heading p { margin: 4px 0 0; color: var(--text-secondary); font-size: 10px; line-height: 1.55; }
.count { display: grid; min-width: 46px; height: 28px; place-items: center; border: 1px solid var(--border); border-radius: 5px; color: var(--accent); background: var(--bg-page); font-family: var(--font-utility); font-size: 10px; font-weight: 700; }
.material-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 18px; min-height: 74px; padding: 13px 4px; border-bottom: 1px solid var(--border-soft); }
.material-row:last-child { border-bottom: 0; }
.material-row.selected { padding-left: 12px; border-left: 3px solid var(--success); }
.material-copy { min-width: 0; }
.material-title { display: flex; align-items: center; flex-wrap: wrap; gap: 7px; }
.material-title span { min-height: 21px; padding: 3px 6px; border: 1px solid var(--border); border-radius: 4px; color: var(--accent); background: var(--accent-light); font-size: 9px; font-weight: 700; }
.material-title strong { overflow-wrap: anywhere; color: var(--text-primary); font-size: 12px; }
.material-copy p { margin: 5px 0 0; color: var(--text-secondary); font-size: 10px; line-height: 1.55; }
.requirement-list { display: flex; flex-wrap: wrap; gap: 5px; margin: 7px 0 0; padding: 0; list-style: none; }
.requirement-list li { padding: 3px 6px; border-radius: 3px; color: var(--info); background: var(--info-light); font-size: 9px; }
.icon-action, .btn-primary, .btn-secondary { display: inline-flex; align-items: center; justify-content: center; gap: 6px; min-height: 36px; padding: 0 11px; border: 1px solid var(--border); border-radius: 6px; color: var(--text-secondary); background: var(--bg-surface); font-size: 11px; font-weight: 650; white-space: nowrap; cursor: pointer; text-decoration: none; }
.icon-action.add { border-color: color-mix(in srgb, var(--success) 28%, var(--border)); color: var(--success); }
.icon-action.remove { border-color: color-mix(in srgb, var(--danger) 28%, var(--border)); color: var(--danger); }
.icon-action.force { border-color: color-mix(in srgb, var(--highlight) 32%, var(--border)); color: var(--highlight); }
.icon-action:hover, .btn-secondary:hover { border-color: var(--accent); color: var(--accent); background: var(--accent-light); }
.gap-band { margin: 0; padding: 17px 20px; border: 1px solid color-mix(in srgb, var(--warning) 28%, var(--border)); border-left: 4px solid var(--warning); border-radius: 7px; background: var(--warning-light); }
.gap-band > div { display: flex; align-items: center; gap: 7px; color: var(--warning); }
.gap-band ul { margin: 9px 0 0; padding-left: 20px; color: var(--text-secondary); font-size: 11px; }
.status-panel { display: grid; justify-items: center; gap: 10px; padding: 48px 20px; border: 1px solid var(--border); border-radius: 7px; background: var(--bg-surface); text-align: center; }
.status-panel p { margin: 0; color: var(--text-secondary); font-size: 11px; }
.error-panel { color: var(--danger); }
.spinner { width: 28px; height: 28px; border: 3px solid var(--border); border-top-color: var(--accent); border-radius: 50%; animation: spin .75s linear infinite; }
.empty-hint { margin: 0; padding: 18px 0; color: var(--text-secondary); font-size: 11px; }
.inline-error { margin: 0; color: var(--danger); font-size: 11px; }
.page-actions { display: flex; justify-content: flex-end; gap: 8px; padding: 12px; border: 1px solid var(--border); border-radius: 7px; background: var(--bg-surface); box-shadow: var(--shadow-sm); }
.btn-primary { border-color: var(--accent); color: #fff; background: var(--accent); }
.btn-primary:hover:not(:disabled) { border-color: var(--accent-hover); background: var(--accent-hover); }
.btn-primary:disabled { opacity: .5; cursor: not-allowed; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 600px) { .selection-page { padding-top: 0; } .selection-header h1 { font-size: 29px; } .selection-route { grid-template-columns: auto 15px auto 15px auto; justify-content: stretch; font-size: 8px; } .selection-section { padding: 18px 15px; } .material-row { align-items: stretch; grid-template-columns: 1fr; } .icon-action { width: 100%; } .page-actions { display: grid; grid-template-columns: 1fr; } }
@media (prefers-reduced-motion: reduce) { .spinner { animation-duration: 1.8s; } }
</style>
