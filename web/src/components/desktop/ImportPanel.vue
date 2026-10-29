<script setup lang="ts">
import { computed, ref } from 'vue'
import { FileJson, Upload } from 'lucide-vue-next'
import { useLocale } from '@/i18n'
import { resolveApiError } from '@/utils/errorMessage'
import {
  applyDesktopImport,
  previewDesktopImport,
  type DesktopImportPreview,
  type DesktopImportResult,
  type DesktopPreviewItem,
} from '@/api/desktop'

const { locale, t } = useLocale()

const fileName = ref('')
const workspacePackage = ref<unknown>(null)
const preview = ref<DesktopImportPreview | null>(null)
const result = ref<DesktopImportResult | null>(null)
const resolutions = ref<Record<string, string>>({})
const parsing = ref(false)
const applying = ref(false)
const error = ref('')

const conflicts = computed(() => preview.value?.items.filter(item => item.decision === 'CONFLICT') ?? [])
const unsupported = computed(() => preview.value?.unsupportedCollections ?? [])
const canApply = computed(() => preview.value !== null && unsupported.value.length === 0 && !applying.value)

/**
 * 说明列：中文界面直接用后端给的精确文案（能区分「同编号版本被占用」这类特例）；
 * 英文界面用本地文案 —— 后端 message 是中文，直接渲染会让界面混语言。
 */
function reasonText(item: DesktopPreviewItem): string {
  if (locale.value === 'zh-CN' && item.reason) return item.reason
  if (item.decision === 'ADD') return t('desktop.import.reasonAdd')
  if (item.decision === 'SKIP') return t('desktop.import.reasonSkip')
  return t('desktop.import.reasonConflict')
}

function decisionLabel(decision: DesktopPreviewItem['decision']): string {
  if (decision === 'ADD') return t('desktop.import.decisionAdd')
  if (decision === 'SKIP') return t('desktop.import.decisionSkip')
  return t('desktop.import.decisionConflict')
}

async function chooseFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  reset()
  if (!file) return
  fileName.value = file.name
  parsing.value = true
  try {
    workspacePackage.value = JSON.parse(await file.text())
  } catch {
    error.value = t('desktop.import.parseFailed')
    workspacePackage.value = null
  } finally {
    parsing.value = false
  }
}

async function runPreview() {
  if (workspacePackage.value === null) return
  parsing.value = true
  error.value = ''
  result.value = null
  try {
    const response = await previewDesktopImport(workspacePackage.value)
    preview.value = response
    // 冲突默认「保留本地」：这是后端未指定时的语义，也让用户必须显式改成覆盖才会丢数据
    resolutions.value = Object.fromEntries(
      conflicts.value.map(item => [item.stableId, 'KEEP_LOCAL']),
    )
  } catch (cause) {
    preview.value = null
    error.value = resolveApiError(cause, 'desktop.import.previewFailed')
  } finally {
    parsing.value = false
  }
}

async function runApply() {
  if (workspacePackage.value === null || !preview.value) return
  applying.value = true
  error.value = ''
  try {
    const response = await applyDesktopImport(workspacePackage.value, resolutions.value)
    result.value = response
    preview.value = null
  } catch (cause) {
    error.value = resolveApiError(cause, 'desktop.import.applyFailed')
  } finally {
    applying.value = false
  }
}

function reset() {
  fileName.value = ''
  workspacePackage.value = null
  preview.value = null
  result.value = null
  resolutions.value = {}
  error.value = ''
}
</script>

<template>
  <section class="desktop-panel">
    <header class="desktop-panel-head">
      <div>
        <h2>{{ t('desktop.import.title') }}</h2>
        <p>{{ t('desktop.import.lead') }}</p>
      </div>
      <label class="btn-neon btn-ghost desktop-file-label">
        <Upload :size="16" />
        {{ t('desktop.import.choose') }}
        <input class="desktop-file-input" type="file" accept="application/json,.json" @change="chooseFile" />
      </label>
    </header>

    <p class="status-line">
      <FileJson :size="15" />
      {{ fileName ? t('desktop.import.selected', { name: fileName }) : t('desktop.import.noFile') }}
    </p>

    <p v-if="error" class="form-error" role="alert">{{ error }}</p>

    <p v-if="result" class="status-line" role="status">
      {{ t('desktop.import.result', {
        inserted: result.inserted, updated: result.updated,
        skipped: result.skipped, keptLocal: result.keptLocal,
      }) }}
    </p>

    <div class="desktop-panel-actions">
      <button type="button" class="btn-neon btn-secondary" :disabled="parsing || workspacePackage === null" @click="runPreview">
        {{ parsing ? t('desktop.import.previewing') : t('desktop.import.preview') }}
      </button>
      <button v-if="preview" type="button" class="btn-neon btn-primary" :disabled="!canApply" @click="runApply">
        {{ applying ? t('desktop.import.applying') : t('desktop.import.apply') }}
      </button>
    </div>

    <p v-if="preview" class="desktop-import-summary" role="status">
      {{ t('desktop.import.summary', {
        added: preview.added, skipped: preview.skipped, conflicts: preview.conflicts,
      }) }}
    </p>

    <p v-if="unsupported.length" class="form-error" role="alert">
      {{ t('desktop.import.unsupported', { collections: unsupported.join(', ') }) }}
    </p>

    <div v-if="preview" class="desktop-import-table-wrap">
      <table class="desktop-import-table">
        <thead>
          <tr>
            <th>{{ t('desktop.import.colCollection') }}</th>
            <th>{{ t('desktop.import.colDecision') }}</th>
            <th>{{ t('desktop.import.colReason') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in preview.items" :key="item.stableId">
            <td>{{ item.collection }}</td>
            <td><span class="desktop-badge" :class="`desktop-badge-${item.decision.toLowerCase()}`">{{ decisionLabel(item.decision) }}</span></td>
            <td>{{ reasonText(item) }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="conflicts.length" class="desktop-conflicts">
      <h3>{{ t('desktop.import.resolutionLabel') }}</h3>
      <label v-for="item in conflicts" :key="item.stableId" class="desktop-conflict-row">
        <span class="desktop-conflict-name">{{ item.collection }} · {{ item.stableId }}</span>
        <select v-model="resolutions[item.stableId]">
          <option value="KEEP_LOCAL">{{ t('desktop.import.keepLocal') }}</option>
          <option value="USE_IMPORTED">{{ t('desktop.import.useImported') }}</option>
        </select>
      </label>
    </div>
  </section>
</template>

<style scoped>
.desktop-panel { display: grid; gap: 16px; padding: 20px; border: 1px solid var(--border); border-radius: 16px; background: var(--bg-surface); }
.desktop-panel-head { display: flex; gap: 16px; align-items: flex-start; justify-content: space-between; flex-wrap: wrap; }
.desktop-panel-head h2 { margin: 0 0 6px; font-size: 18px; }
.desktop-panel-head p { margin: 0; color: var(--text-secondary); }
.desktop-panel-actions { display: flex; gap: 8px; }
.desktop-file-label { position: relative; display: inline-flex; align-items: center; gap: 6px; cursor: pointer; }
.desktop-file-input { position: absolute; inset: 0; opacity: 0; cursor: pointer; }
.status-line { display: inline-flex; align-items: center; gap: 6px; margin: 0; color: var(--text-secondary); }
.desktop-import-summary { margin: 0; font-weight: 600; }
.desktop-import-table-wrap { overflow-x: auto; }
.desktop-import-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.desktop-import-table th, .desktop-import-table td { padding: 8px 10px; border-bottom: 1px solid var(--border); text-align: left; vertical-align: top; }
.desktop-import-table th { color: var(--text-secondary); font-weight: 600; }
.desktop-badge { padding: 1px 8px; border-radius: 999px; font-size: 11px; background: var(--bg-muted, rgba(0, 0, 0, .06)); }
.desktop-badge-add { background: rgba(22, 163, 74, .12); }
.desktop-badge-conflict { background: rgba(217, 119, 6, .16); }
.desktop-conflicts { display: grid; gap: 8px; }
.desktop-conflicts h3 { margin: 0; font-size: 14px; }
.desktop-conflict-row { display: flex; gap: 12px; align-items: center; justify-content: space-between; flex-wrap: wrap; }
.desktop-conflict-name { color: var(--text-secondary); font-size: 12px; }
</style>
