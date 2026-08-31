<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, ArrowLeftRight, Clipboard, RotateCcw } from 'lucide-vue-next'
import { getResume, getResumeVersion, listVersions, restoreResumeVersion, type ResumeSummary, type ResumeVersionSummary } from '@/api/resume'
import { useLocale } from '@/i18n'
import { diffResumeVersions, summarizeDiff, type EntryDiff, type SectionDiff } from '@/utils/resumeDiff'

const { locale, t } = useLocale()
const route = useRoute()
const router = useRouter()
const resumeId = Number(route.params.id)

const resume = ref<ResumeSummary | null>(null)
const versions = ref<ResumeVersionSummary[]>([])
const baseVersionId = ref<number | null>(null)
const compareVersionId = ref<number | null>(null)
const baseJson = ref<Record<string, unknown> | null>(null)
const compareJson = ref<Record<string, unknown> | null>(null)
const loading = ref(false)
const error = ref('')
const restoring = ref(false)
const copyStatus = ref('')
const expandedSections = ref<Set<string>>(new Set())
const onlyChanged = ref(false)

const diffs = computed<SectionDiff[]>(() => {
  if (!baseJson.value || !compareJson.value) return []
  return diffResumeVersions(baseJson.value, compareJson.value)
})

const summary = computed(() => summarizeDiff(diffs.value))

const visibleDiffs = computed(() => onlyChanged.value ? diffs.value.filter((d) => d.type !== 'UNCHANGED') : diffs.value)

const baseVersion = computed(() => versions.value.find((v) => v.id === baseVersionId.value) ?? null)
const compareVersion = computed(() => versions.value.find((v) => v.id === compareVersionId.value) ?? null)

const sectionLabels: Record<string, string> = {
  basics: t('resumeEditor.basicsLabel'),
  objective: t('resumeEditor.objectiveLabel'),
  links: t('resumeEditor.linksLabel'),
  work: t('resumeEditor.workLabel'),
  volunteering: t('resumeEditor.volunteeringLabel'),
  skills: t('resumeEditor.skillsLabel'),
  projects: t('resumeEditor.projectsLabel'),
  education: t('resumeEditor.educationLabel'),
  courses: t('resumeEditor.coursesLabel'),
  certificates: t('resumeEditor.certificatesLabel'),
  publications: t('resumeEditor.publicationsLabel'),
  awards: t('resumeEditor.awardsLabel'),
  languages: t('resumeEditor.languagesLabel'),
  customSections: t('resumeEditor.customSectionsLabel'),
}

function sectionLabel(key: string) {
  return sectionLabels[key] ?? key
}

function formatDate(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat(locale.value, { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(date)
}

function sourceLabel(source: ResumeVersionSummary['sourceType']) {
  return t({
    MANUAL: 'resumeDetail.sourceManual', AI_OPTIMIZED: 'resumeDetail.sourceAiOptimized', JD_CUSTOMIZED: 'resumeDetail.sourceJdCustomized',
    MATERIAL_CUSTOMIZED: 'resumeDetail.sourceMaterialCustomized', RESTORED: 'resumeDetail.sourceRestored',
  }[source])
}

function toggleSection(key: string) {
  const next = new Set(expandedSections.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  expandedSections.value = next
}

function isExpanded(key: string) {
  return expandedSections.value.has(key)
}

async function loadJson(versionId: number): Promise<Record<string, unknown>> {
  const response = await getResumeVersion(versionId)
  return (response.data.data.resumeJson ?? {}) as Record<string, unknown>
}

async function loadDiffs() {
  if (baseVersionId.value === null || compareVersionId.value === null) return
  loading.value = true
  error.value = ''
  try {
    const [base, compare] = await Promise.all([
      loadJson(baseVersionId.value),
      loadJson(compareVersionId.value),
    ])
    baseJson.value = base
    compareJson.value = compare
    expandedSections.value = new Set(
      diffResumeVersions(base, compare).filter((d) => d.type !== 'UNCHANGED').map((d) => d.sectionKey),
    )
  } catch {
    error.value = t('resumeCompare.loadError')
  } finally {
    loading.value = false
  }
}

async function onBaseChange() {
  await loadDiffs()
}
async function onCompareChange() {
  await loadDiffs()
}

function switchSides() {
  const tmp = baseVersionId.value
  baseVersionId.value = compareVersionId.value
  compareVersionId.value = tmp
  void loadDiffs()
}

async function restoreVersion(versionId: number) {
  if (!window.confirm(t('resumeDetail.restoreConfirm').replace('{no}', String(versions.value.find((v) => v.id === versionId)?.versionNo ?? versionId)))) return
  restoring.value = true
  error.value = ''
  try {
    await restoreResumeVersion(resumeId, versionId)
    const fresh = (await listVersions(resumeId, false)).data.data
    versions.value = fresh
    await loadDiffs()
  } catch {
    error.value = t('resumeDetail.restoreError')
  } finally {
    restoring.value = false
  }
}

async function copySection(side: 'base' | 'compare', section: SectionDiff) {
  const value = side === 'base' ? section.base : section.compare
  if (value === undefined || value === null) return
  const text = typeof value === 'string' ? value : JSON.stringify(value, null, 2)
  try {
    await navigator.clipboard.writeText(text)
    copyStatus.value = t('resumeCompare.copied')
  } catch {
    copyStatus.value = t('communication.clipboardError')
  }
}

function fieldLabel(section: SectionDiff, key: string) {
  const labels: Record<string, string> = {
    name: t('resumeEditor.name'), title: t('draftFields.title'), company: t('draftFields.company'),
    position: t('draftFields.position'), startDate: t('draftFields.startDate'), endDate: t('draftFields.endDate'),
    summary: t('draftFields.summary'), description: t('draftFields.description'), highlights: t('draftFields.highlights'),
    level: t('draftFields.level'), keywords: t('draftFields.keywords'), institution: t('draftFields.school'),
    area: t('draftFields.major'), studyType: t('draftFields.degree'), issuer: t('draftFields.issuer'),
    date: t('draftFields.date'), url: t('draftFields.url'), language: t('resumeEditor.language'),
    fluency: t('draftFields.level'), items: t('resumeCompare.items'),
  }
  return labels[key] ?? key
}

function fieldClass(entry: EntryDiff, key: string, side: 'base' | 'compare'): string {
  const field = entry.fields.find((f) => f.key === key)
  if (!field || field.type === 'MODIFIED') return 'field-modified'
  if (side === 'base' && field.type === 'REMOVED') return 'field-removed'
  if (side === 'compare' && field.type === 'ADDED') return 'field-added'
  return ''
}

function displayValue(value: unknown): string {
  if (value === undefined || value === null) return '—'
  if (typeof value === 'string') return value
  if (Array.isArray(value)) return value.map((item) => typeof item === 'string' ? item : JSON.stringify(item)).join(' · ')
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

onMounted(async () => {
  try {
    const [resumeResponse, versionResponse] = await Promise.all([
      getResume(resumeId),
      listVersions(resumeId, false),
    ])
    resume.value = resumeResponse.data.data
    versions.value = versionResponse.data.data
    const requestedBase = Number(route.query.base)
    const requestedCompare = Number(route.query.compare)
    baseVersionId.value = Number.isInteger(requestedBase) && versions.value.some((v) => v.id === requestedBase)
      ? requestedBase
      : (resume.value?.currentVersionId ?? versions.value[0]?.id ?? null)
    compareVersionId.value = Number.isInteger(requestedCompare) && versions.value.some((v) => v.id === requestedCompare)
      ? requestedCompare
      : versions.value.find((v) => v.id !== baseVersionId.value)?.id ?? baseVersionId.value
    await loadDiffs()
  } catch {
    error.value = t('resumeCompare.loadError')
  }
})

watch([baseVersionId, compareVersionId], () => {
  if (baseVersionId.value !== null && compareVersionId.value !== null) {
    void loadDiffs()
  }
})
</script>

<template>
  <section class="workspace-page compare-page">
    <header class="compare-heading">
      <button class="compare-back" type="button" @click="router.push({ name: 'resume-detail', params: { id: String(resumeId) } })">
        <ArrowLeft :size="15" />{{ t('resumeCompare.backToDetail') }}
      </button>
      <div>
        <p class="eyebrow"><ArrowLeftRight :size="14" />{{ t('resumeCompare.eyebrow') }}</p>
        <h1>{{ t('resumeCompare.title') }}</h1>
        <p class="page-lead">{{ resume?.title }} · {{ t('resumeCompare.subtitle') }}</p>
      </div>
      <button class="btn-neon btn-ghost" type="button" @click="switchSides"><ArrowLeftRight :size="16" />{{ t('resumeCompare.switchSides') }}</button>
    </header>

    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <p v-if="copyStatus" class="disclaimer">{{ copyStatus }}</p>

    <form v-if="versions.length" class="compare-selectors" @submit.prevent>
      <label>{{ t('resumeCompare.baseVersion') }}
        <select :value="baseVersionId ?? ''" @change="baseVersionId = Number(($event.target as HTMLSelectElement).value); onBaseChange()">
          <option v-for="v in versions" :key="v.id" :value="v.id">v{{ v.versionNo }} · {{ sourceLabel(v.sourceType) }} · {{ formatDate(v.createdAt) }}</option>
        </select>
      </label>
      <label>{{ t('resumeCompare.compareVersion') }}
        <select :value="compareVersionId ?? ''" @change="compareVersionId = Number(($event.target as HTMLSelectElement).value); onCompareChange()">
          <option v-for="v in versions" :key="v.id" :value="v.id">v{{ v.versionNo }} · {{ sourceLabel(v.sourceType) }} · {{ formatDate(v.createdAt) }}</option>
        </select>
      </label>
      <label class="changed-only-toggle"><input v-model="onlyChanged" type="checkbox" />{{ t('resumeCompare.onlyChanged') }}</label>
    </form>

    <div v-if="loading" class="empty-state">{{ t('resumeCompare.loading') }}</div>

    <template v-else-if="diffs.length">
      <section class="diff-summary" :aria-label="t('resumeCompare.summary')">
        <div><span>{{ t('resumeCompare.summaryChanged') }}</span><strong>{{ summary.changedSections }}</strong></div>
        <div class="diff-added"><span>{{ t('resumeCompare.summaryAdded') }}</span><strong>{{ summary.addedItems }}</strong></div>
        <div class="diff-removed"><span>{{ t('resumeCompare.summaryRemoved') }}</span><strong>{{ summary.removedItems }}</strong></div>
        <div class="diff-modified"><span>{{ t('resumeCompare.summaryModified') }}</span><strong>{{ summary.modifiedItems }}</strong></div>
      </section>

      <div class="compare-sections">
        <article v-for="section in visibleDiffs" :key="section.sectionKey" class="compare-section" :class="`section-${section.type.toLowerCase()}`">
          <header class="compare-section-header" role="button" tabindex="0" @click="toggleSection(section.sectionKey)" @keydown.enter="toggleSection(section.sectionKey)">
            <span class="section-badge" :class="section.type.toLowerCase()">{{ t(`resumeCompare.sectionType.${section.type}`) }}</span>
            <h2>{{ sectionLabel(section.sectionKey) }}</h2>
            <div class="section-actions">
              <button class="icon-copy" type="button" :title="t('resumeCompare.copyLeft')" :aria-label="t('resumeCompare.copyLeft')" @click.stop="copySection('base', section)"><Clipboard :size="14" /></button>
              <button class="icon-copy" type="button" :title="t('resumeCompare.copyRight')" :aria-label="t('resumeCompare.copyRight')" @click.stop="copySection('compare', section)"><Clipboard :size="14" /></button>
            </div>
          </header>

          <!-- 对象章节：字段级并排 -->
          <div v-if="isExpanded(section.sectionKey) && section.entries.length === 0" class="field-diff-list">
            <div v-for="field in section.fields" :key="field.key" class="field-diff-row" :class="`field-${field.type.toLowerCase()}`">
              <div class="field-label">{{ fieldLabel(section, field.key) }}</div>
              <div class="field-side base"><span v-if="field.type === 'ADDED'" class="empty-mark">—</span><template v-else>{{ displayValue(field.baseValue) }}</template></div>
              <div class="field-side compare"><span v-if="field.type === 'REMOVED'" class="empty-mark">—</span><template v-else>{{ displayValue(field.compareValue) }}</template></div>
            </div>
          </div>

          <!-- 数组章节：条目并排 -->
          <div v-else-if="isExpanded(section.sectionKey)" class="entry-diff-list">
            <div v-for="entry in section.entries" :key="entry.key" class="entry-diff" :class="`entry-${entry.type.toLowerCase()}`">
              <div class="entry-head">
                <span class="entry-badge" :class="entry.type.toLowerCase()">{{ t(`resumeCompare.sectionType.${entry.type}`) }}</span>
                <span class="entry-key">{{ entry.key }}</span>
              </div>
              <div class="entry-sides">
                <div class="entry-side base">
                  <template v-if="entry.baseEntry">
                    <p v-for="(value, key) in entry.baseEntry" :key="key" class="entry-field" :class="fieldClass(entry, key, 'base')">
                      <span>{{ fieldLabel(section, key) }}</span>{{ displayValue(value) }}
                    </p>
                  </template>
                  <span v-else class="empty-mark">{{ t('resumeCompare.noContent') }}</span>
                </div>
                <div class="entry-side compare">
                  <template v-if="entry.compareEntry">
                    <p v-for="(value, key) in entry.compareEntry" :key="key" class="entry-field" :class="fieldClass(entry, key, 'compare')">
                      <span>{{ fieldLabel(section, key) }}</span>{{ displayValue(value) }}
                    </p>
                  </template>
                  <span v-else class="empty-mark">{{ t('resumeCompare.noContent') }}</span>
                </div>
              </div>
            </div>
          </div>
        </article>
      </div>

      <div class="compare-actions">
        <button class="btn-neon btn-secondary" :disabled="restoring" @click="baseVersionId !== null && restoreVersion(baseVersionId)"><RotateCcw :size="16" />{{ t('resumeCompare.restoreBase', { no: baseVersion?.versionNo ?? '' }) }}</button>
        <button class="btn-neon btn-primary" :disabled="restoring" @click="compareVersionId !== null && restoreVersion(compareVersionId)"><RotateCcw :size="16" />{{ t('resumeCompare.restoreCompare', { no: compareVersion?.versionNo ?? '' }) }}</button>
      </div>
    </template>

    <p v-else-if="!loading && !error" class="empty-state">{{ t('resumeCompare.noVersions') }}</p>
  </section>
</template>

<style scoped>
.compare-page { width: min(100%, 1200px); max-width: 1200px; gap: 22px; }
.compare-heading { display: grid; grid-template-columns: auto 1fr auto; align-items: end; gap: 16px; padding-bottom: 22px; border-bottom: 1px solid var(--border); }
.compare-back { display: inline-flex; align-items: center; gap: 6px; align-self: center; color: var(--text-secondary); font-size: 11px; font-weight: 650; background: transparent; border: 0; cursor: pointer; }
.compare-back:hover { color: var(--accent); }
.compare-heading h1 { margin: 5px 0 7px; font-family: var(--font-display); font-size: 34px; letter-spacing: 0; }
.compare-heading .page-lead { max-width: 650px; font-size: 12px; }
.compare-selectors { display: grid; grid-template-columns: 1fr 1fr auto; align-items: end; gap: 12px; padding: 14px 16px; border-block: 1px solid var(--border-soft); }
.compare-selectors label { display: grid; gap: 6px; color: var(--text-secondary); font-size: 11px; font-weight: 650; }
.compare-selectors select { width: 100%; padding: 9px; border: 1px solid var(--border); border-radius: 6px; background: var(--bg-input); color: var(--text-primary); font: inherit; font-size: 11px; }
.changed-only-toggle { display: flex !important; flex-direction: row; align-items: center; gap: 7px !important; font-size: 11px !important; }
.diff-summary { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); border-block: 1px solid var(--border); }
.diff-summary div { display: flex; align-items: baseline; justify-content: space-between; gap: 16px; padding: 13px 18px; border-right: 1px solid var(--border); }
.diff-summary div:last-child { border-right: 0; }
.diff-summary span { color: var(--text-tertiary); font-size: 10px; font-weight: 700; }
.diff-summary strong { font-family: var(--font-utility); font-size: 20px; }
.diff-summary .diff-added strong { color: var(--success); }
.diff-summary .diff-removed strong { color: var(--error, #ef4444); }
.diff-summary .diff-modified strong { color: var(--highlight, #d97706); }
.compare-sections { display: grid; gap: 12px; }
.compare-section { border: 1px solid var(--border); border-radius: 7px; background: var(--bg-surface); }
.compare-section-header { display: grid; grid-template-columns: auto 1fr auto; align-items: center; gap: 12px; padding: 13px 16px; cursor: pointer; }
.compare-section-header h2 { margin: 0; font-size: 14px; }
.section-badge, .entry-badge { padding: 3px 8px; border-radius: 11px; font-family: var(--font-utility); font-size: 9px; font-weight: 700; }
.section-badge.unchanged, .entry-badge.unchanged { color: var(--text-tertiary); background: var(--bg-page); border: 1px solid var(--border); }
.section-badge.added, .entry-badge.added { color: var(--success); background: var(--success-light); }
.section-badge.removed, .entry-badge.removed { color: var(--error, #ef4444); background: var(--danger-light, #fee2e2); }
.section-badge.changed, .entry-badge.changed { color: var(--highlight, #d97706); background: var(--highlight-light, #fef3c7); }
.section-actions { display: flex; gap: 6px; }
.icon-copy { display: grid; width: 28px; height: 28px; place-items: center; border: 1px solid var(--border); border-radius: 5px; color: var(--text-tertiary); background: transparent; cursor: pointer; }
.icon-copy:hover { color: var(--accent); border-color: var(--accent); }
.field-diff-list, .entry-diff-list { display: grid; gap: 8px; padding: 6px 16px 16px; border-top: 1px solid var(--border-soft); }
.field-diff-row { display: grid; grid-template-columns: 150px 1fr 1fr; gap: 12px; align-items: start; padding: 8px 10px; border-radius: 5px; background: var(--bg-page); }
.field-diff-row.field-modified { background: var(--highlight-light, #fef3c7); }
.field-diff-row.field-added { background: var(--success-light); }
.field-diff-row.field-removed { background: var(--danger-light, #fee2e2); }
.field-label { color: var(--text-tertiary); font-size: 10px; font-weight: 700; }
.field-side { color: var(--text-secondary); font-size: 11px; line-height: 1.5; white-space: pre-wrap; overflow-wrap: anywhere; }
.field-side.base { border-right: 1px solid var(--border-soft); padding-right: 10px; }
.entry-diff { border: 1px solid var(--border); border-radius: 6px; overflow: hidden; }
.entry-head { display: flex; align-items: center; gap: 10px; padding: 8px 12px; border-bottom: 1px solid var(--border-soft); background: var(--bg-page); }
.entry-key { overflow: hidden; color: var(--text-tertiary); font-family: var(--font-utility); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.entry-sides { display: grid; grid-template-columns: 1fr 1fr; }
.entry-side { display: grid; align-content: start; gap: 4px; padding: 10px 12px; }
.entry-side.base { border-right: 1px solid var(--border-soft); }
.entry-field { display: grid; gap: 2px; margin: 0; color: var(--text-secondary); font-size: 10px; line-height: 1.5; white-space: pre-wrap; overflow-wrap: anywhere; }
.entry-field span { color: var(--text-tertiary); font-size: 9px; font-weight: 700; }
.entry-field.field-added { background: var(--success-light); }
.entry-field.field-removed { background: var(--danger-light, #fee2e2); }
.entry-field.field-modified { background: var(--highlight-light, #fef3c7); }
.empty-mark { color: var(--text-tertiary); font-size: 10px; }
.compare-actions { display: flex; justify-content: flex-end; gap: 10px; }
@media (max-width: 820px) { .compare-heading { grid-template-columns: 1fr; } .compare-back { justify-self: start; } .compare-selectors { grid-template-columns: 1fr; } .diff-summary { grid-template-columns: repeat(2, 1fr); } .field-diff-row { grid-template-columns: 90px 1fr; } .field-side.base { border-right: 0; border-bottom: 1px solid var(--border-soft); padding-right: 0; padding-bottom: 8px; } .entry-sides { grid-template-columns: 1fr; } .entry-side.base { border-right: 0; border-bottom: 1px solid var(--border-soft); } }
</style>
