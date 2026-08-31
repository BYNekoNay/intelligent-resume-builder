<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAiTaskStore } from '@/stores/aiTask'
import { getTask, confirmTask, rejectTask, retryTask, type AiTask } from '@/api/ai'
import { useTaskPolling } from '@/composables/useTaskPolling'
import { listResumesByJd } from '@/api/resume'
import { useDraftReview } from '@/composables/useDraftReview'
import { Check, ClipboardCheck, Sparkles } from 'lucide-vue-next'
import { useLocale } from '@/i18n'
import QualitySummaryCard from '@/components/generation/QualitySummaryCard.vue'
import DraftSectionReview from '@/components/generation/DraftSectionReview.vue'
import DraftEditDialog from '@/components/generation/DraftEditDialog.vue'
import ExistingJdDialog from '@/components/generation/ExistingJdDialog.vue'

const { t } = useLocale()

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

const pollingTimedOut = ref(false)
const polling = useTaskPolling<AiTask>()

const { draftItems, pendingCount, customTitle, mobileNavigationOpen, parseDraft, openJdDialog, closeJdDialog, resetDraftReview } = useDraftReview()

onMounted(async () => {
  resetDraftReview()
  const tid = route.query.taskId
  if (!tid) {
    error.value = t('generationConfirm.missingTaskId')
    loading.value = false
    return
  }
  await loadTask(Number(tid))
})

async function loadTask(id: number) {
  loading.value = true
  error.value = ''
  try {
    const res = await getTask(id)
    task.value = res.data.data
    if (task.value.status === 'SUCCESS' && task.value.confirmationStatus === 'PENDING') {
      resultJson.value = task.value.resultJson
      parseDraft(resultJson.value)
    } else if (task.value.status === 'PENDING' || task.value.status === 'RUNNING') {
      startPolling(id)
    } else if (task.value.status === 'FAILED') {
      error.value = task.value.errorMessage || t('generationConfirm.aiGenerationFailed')
    }
  } catch (e: any) {
    error.value = e.response?.data?.message || t('generationConfirm.loadTaskFailed')
  } finally {
    loading.value = false
  }
}

function startPolling(id: number) {
  pollingTimedOut.value = false
  polling.start({
    taskId: id,
    fetchTask: async (taskId) => (await getTask(taskId)).data.data,
    onTask: (next) => {
      task.value = next
      if (next.status === 'SUCCESS') {
        resultJson.value = next.resultJson
        parseDraft(resultJson.value)
        loading.value = false
      } else if (next.status === 'FAILED') {
        error.value = next.errorMessage || t('generationConfirm.aiGenerationFailed')
        loading.value = false
      } else if (next.status === 'CANCELLED') {
        error.value = t('common.taskCancelled')
        loading.value = false
      }
    },
    shouldStop: (next) => next.status === 'SUCCESS' || next.status === 'FAILED' || next.status === 'CANCELLED',
    onTimeout: () => {
      pollingTimedOut.value = true
      error.value = t('common.taskTimeout')
      loading.value = false
    },
  })
}

async function handleConfirm() {
  if (pendingCount.value > 0) {
    error.value = t('generationConfirm.pendingItemsError').replace('{count}', String(pendingCount.value))
    return
  }

  // Check if same JD has existing resumes
  const jdId = extractJdId()
  if (jdId) {
    try {
      const res = await listResumesByJd(jdId)
      if (res.data.data && res.data.data.length > 0) {
        openJdDialog(res.data.data)
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
  closeJdDialog()
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
    error.value = e.response?.data?.message || t('generationConfirm.confirmFailed')
  } finally {
    confirming.value = false
  }
}

async function handleReject() {
  if (!window.confirm(t('generationConfirm.rejectConfirm'))) return
  rejecting.value = true
  try {
    await rejectTask(task.value.id, task.value.updatedAt)
    taskStore.clear()
    router.push('/generate')
  } catch (e: any) {
    error.value = e.response?.data?.message || t('generationConfirm.operationFailed')
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
    error.value = e.response?.data?.message || t('generationConfirm.retryFailed')
  }
}
</script>

<template>
  <div class="confirm-page">
    <header class="confirm-header">
      <p class="eyebrow"><ClipboardCheck :size="14" /> {{ t('generationConfirm.eyebrow') }}</p>
      <h1>{{ t('generationConfirm.pageTitle') }}</h1>
      <p class="subtitle">{{ t('generationConfirm.pageSubtitle') }}</p>
      <div class="confirm-route" aria-hidden="true"><span class="done"><Check :size="12" />{{ t('generationWorkbench.stepTargetJob') }}</span><i></i><span class="done"><Check :size="12" />{{ t('generationWorkbench.stepMaterialScope') }}</span><i></i><span class="active">{{ t('generationConfirm.reviewStep') }}</span></div>
    </header>

    <!-- Loading / Polling -->
    <div v-if="loading" class="status-card">
      <div class="spinner-lg"></div>
      <Sparkles :size="20" />
      <p>{{ t('generationConfirm.generating') }}</p>
      <p class="hint">{{ t('generationConfirm.generatingHint') }}</p>
    </div>

    <!-- Error / Failed -->
    <div v-else-if="error && (pollingTimedOut || !task || task.status === 'FAILED' || task.status === 'CANCELLED')" class="status-card error">
      <p class="error-text">{{ error }}</p>
      <button class="btn-primary" @click="handleRetry">{{ t('generationConfirm.retryGenerate') }}</button>
    </div>

    <!-- Draft confirmation -->
    <div v-else-if="task && task.status === 'SUCCESS'" class="draft-container" @keydown.esc="mobileNavigationOpen = false">
      <QualitySummaryCard />
      <DraftSectionReview
        :error="error"
        :confirming="confirming"
        :rejecting="rejecting"
        @confirm="handleConfirm"
        @reject="handleReject"
      />
    </div>

    <!-- Same-JD Dialog -->
    <ExistingJdDialog @confirm="doConfirm" />

    <!-- Edit Dialog -->
    <DraftEditDialog />
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

/* Human review surface for generated content. */
.confirm-page { display: grid; gap: 24px; width: min(100%, 920px); max-width: 920px; margin: 0 auto; padding: 8px 0 52px; }
.confirm-header { display: grid; gap: 0; padding-bottom: 22px; border-bottom: 1px solid var(--border); }
.confirm-header .eyebrow { justify-self: start; }
.confirm-header h1 { margin: 5px 0 7px; color: var(--text-primary); font-family: var(--font-display); font-size: 34px; font-weight: 700; letter-spacing: 0; }
.confirm-header .subtitle { max-width: 690px; margin: 0; color: var(--text-secondary); font-size: 12px; line-height: 1.65; }
.confirm-route { display: grid; grid-template-columns: auto 34px auto 34px auto; align-items: center; justify-content: end; gap: 7px; margin-top: 20px; color: var(--text-tertiary); font-family: var(--font-utility); font-size: 9px; font-weight: 700; }
.confirm-route span { display: inline-flex; align-items: center; gap: 4px; }
.confirm-route i { height: 1px; background: var(--border); }
.confirm-route .done { color: var(--text-primary); }
.confirm-route .active { color: var(--accent); }
.draft-container { display: grid; gap: 18px; }
.status-card { display: grid; justify-items: center; gap: 9px; padding: 48px 20px; border: 1px solid var(--border); border-radius: 7px; color: var(--accent); background: var(--bg-surface); text-align: center; }
.status-card p { margin: 0; color: var(--text-primary); font-size: 13px; font-weight: 650; }
.status-card .hint { color: var(--text-tertiary); font-size: 10px; font-weight: 500; }
.status-card.error { border-color: color-mix(in srgb, var(--danger) 28%, var(--border)); color: var(--danger); background: var(--danger-light); }
.spinner-lg { width: 30px; height: 30px; margin: 0 0 5px; border-color: var(--border); border-top-color: var(--accent); }
.btn-primary, .btn-secondary, .btn-small { display: inline-flex; align-items: center; justify-content: center; min-height: 36px; padding: 0 13px; border: 1px solid var(--border); border-radius: 6px; font-size: 11px; font-weight: 650; cursor: pointer; }
.btn-primary, .btn-small { border-color: var(--accent); color: #fff; background: var(--accent); }

.confirm-page { gap: 14px; width: min(100%, 1180px); max-width: 1180px; padding-bottom: 28px; }
.confirm-header { grid-template-columns: minmax(0, 1fr) auto; padding-bottom: 13px; }
.confirm-header .eyebrow,
.confirm-header h1,
.confirm-header .subtitle { grid-column: 1; }
.confirm-header h1 { margin: 3px 0 4px; font-size: 28px; }
.confirm-header .subtitle { font-size: 11px; }
.confirm-route { grid-column: 2; grid-row: 1 / 4; align-self: center; margin: 0 0 0 28px; }
.draft-container { gap: 10px; min-width: 0; }

@keyframes spin {
  to { transform: rotate(360deg); }
}

@media (max-width: 900px) {
  .confirm-route { display: none; }
}

@media (max-width: 767px) {
  .confirm-page { gap: 10px; padding-top: 0; }
  .confirm-header { display: block; padding-bottom: 10px; }
  .confirm-header h1 { font-size: 25px; }
  .confirm-header .subtitle { display: none; }
}

@media (max-width: 560px) {
  .confirm-page { padding-top: 0; }
  .confirm-header h1 { font-size: 29px; }
  .confirm-route { grid-template-columns: auto 15px auto 15px auto; justify-content: stretch; font-size: 8px; }
}
</style>
