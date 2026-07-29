<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { Database, Plus, Search, SlidersHorizontal } from 'lucide-vue-next'
import {
  createMaterial,
  deleteMaterial,
  getMaterial,
  listMaterials,
  searchMaterials,
  updateMaterial,
  type CareerMaterial,
  type CareerMaterialPayload,
  type CareerMaterialSearchPage,
  type CareerMaterialSort,
  type CareerMaterialSummary,
  type MaterialType,
  type UsagePreference,
} from '@/api/careerMaterial'
import { emptyPersonalProfile, getPersonalProfile, getPersonalProfileImportSuggestion, normalizePersonalProfile, updatePersonalProfile, type PersonalProfile } from '@/api/personalProfile'
import { listResumes, type ResumeSummary } from '@/api/resume'
import CareerMaterialNavigation from '@/components/career-material/CareerMaterialNavigation.vue'
import CareerMaterialList from '@/components/career-material/CareerMaterialList.vue'
import CareerMaterialDetail from '@/components/career-material/CareerMaterialDetail.vue'
import CareerMaterialForm from '@/components/career-material/CareerMaterialForm.vue'
import CareerProfileEditor from '@/components/career-material/CareerProfileEditor.vue'
import { MATERIAL_TYPE_OPTIONS, USAGE_OPTIONS } from '@/components/career-material/options'
import { useLocale } from '@/i18n'

const EMPTY_RESULT: CareerMaterialSearchPage = {
  items: [], page: 0, size: 25, totalElements: 0, totalPages: 0, typeCounts: {},
}
const SORT_OPTIONS: { value: CareerMaterialSort; key: string }[] = [
  { value: 'updatedAt,desc', key: 'careerMaterial.sortNewest' },
  { value: 'updatedAt,asc', key: 'careerMaterial.sortOldest' },
  { value: 'title,asc', key: 'careerMaterial.sortTitle' },
]
const VALID_TYPES = new Set(MATERIAL_TYPE_OPTIONS.map(option => option.value))
const VALID_USAGES = new Set(USAGE_OPTIONS.map(option => option.value))
const VALID_SORTS = new Set(SORT_OPTIONS.map(option => option.value))

const { t } = useLocale()
const route = useRoute()
const router = useRouter()
const result = ref<CareerMaterialSearchPage>({ ...EMPTY_RESULT })
const searchInput = ref('')
const loading = ref(false)
const workspaceError = ref('')
const detailError = ref('')
const mutationError = ref('')
const detail = ref<CareerMaterial | null>(null)
const detailLoading = ref(false)
const panelMode = ref<'none' | 'create' | 'edit'>('none')
const initialCreateType = ref<MaterialType>('WORK_EXPERIENCE')
const materialDirty = ref(false)
const saving = ref(false)
const activeProfile = ref(false)
const profile = ref<PersonalProfile>(emptyPersonalProfile())
const profileBaseline = ref(JSON.stringify(profile.value))
const profileLoading = ref(true)
const profileSaving = ref(false)
const profileMessage = ref('')
const resumes = ref<ResumeSummary[]>([])
const importResumeId = ref<number | null>(null)
const relationMaterials = ref<CareerMaterialSummary[]>([])
let searchRequest = 0
let detailRequest = 0
let paneEpoch = 0
let profileRequest = 0
let detailLoad: { id: number; promise: Promise<CareerMaterial | null> } | null = null
let resumesLoaded = false
let relationMaterialsLoaded = false
let mounted = true
const deletingIds = new Set<number>()
let searchTimer: ReturnType<typeof setTimeout> | undefined

const routeType = computed<'' | MaterialType>(() => {
  const value = typeof route.query.type === 'string' ? route.query.type : ''
  return VALID_TYPES.has(value as MaterialType) ? value as MaterialType : ''
})
const routeUsage = computed<'' | UsagePreference>(() => {
  const value = typeof route.query.usage === 'string' ? route.query.usage : ''
  return VALID_USAGES.has(value as UsagePreference) ? value as UsagePreference : ''
})
const routeSort = computed<CareerMaterialSort>(() => {
  const value = typeof route.query.sort === 'string' ? route.query.sort : ''
  return VALID_SORTS.has(value as CareerMaterialSort) ? value as CareerMaterialSort : 'updatedAt,desc'
})
const routePage = computed(() => {
  const value = Number(route.query.page)
  return Number.isInteger(value) && value > 0 ? value - 1 : 0
})
const selectedId = computed(() => {
  const value = Number(route.query.selected)
  return Number.isInteger(value) && value > 0 ? value : null
})
const routeQueryText = computed(() => typeof route.query.q === 'string' ? route.query.q : '')
const totalMaterials = computed(() => Object.values(result.value.typeCounts).reduce((total, count) => total + (count ?? 0), 0))
const profileDirty = computed(() => JSON.stringify(profile.value) !== profileBaseline.value)
const hasUnsavedChanges = computed(() => materialDirty.value || (activeProfile.value && profileDirty.value))
const panelOpen = computed(() => panelMode.value !== 'none' || selectedId.value !== null)
const materialForForm = computed(() => panelMode.value === 'edit' ? detail.value : null)

function workspaceQueryKey() {
  return JSON.stringify({ q: routeQueryText.value, type: routeType.value, usage: routeUsage.value, page: routePage.value, sort: routeSort.value })
}

function cleanQuery(patch: Record<string, string | number | null | undefined>) {
  const next: Record<string, string> = {}
  for (const [key, value] of Object.entries({ ...route.query, ...patch })) {
    if (value !== null && value !== undefined && value !== '') next[key] = String(value)
  }
  return next
}

async function updateQuery(patch: Record<string, string | number | null | undefined>) {
  await router.replace({ query: cleanQuery(patch) })
}

function confirmDiscard() {
  return !hasUnsavedChanges.value || window.confirm(t('careerMaterial.unsavedConfirm'))
}

async function loadWorkspace() {
  if (!mounted) return
  const request = ++searchRequest
  loading.value = true
  workspaceError.value = ''
  try {
    const response = await searchMaterials({
      q: routeQueryText.value.trim() || undefined,
      type: routeType.value || undefined,
      usagePreference: routeUsage.value || undefined,
      page: routePage.value,
      size: 25,
      sort: routeSort.value,
    })
    if (request !== searchRequest) return
    result.value = response.data.data
    if (result.value.totalPages > 0 && routePage.value >= result.value.totalPages) {
      if (request !== searchRequest) return
      await updateQuery({ page: result.value.totalPages })
    }
  } catch {
    if (request === searchRequest) workspaceError.value = t('careerMaterial.libraryLoadError')
  } finally {
    if (request === searchRequest) loading.value = false
  }
}

async function loadDetail(id: number): Promise<CareerMaterial | null> {
  if (detail.value?.id === id) return detail.value
  if (detailLoad?.id === id) return detailLoad.promise
  const request = ++detailRequest
  detailLoading.value = true
  detailError.value = ''
  const promise = (async () => {
    try {
      const material = (await getMaterial(id)).data.data
      if (request !== detailRequest) return null
      detail.value = material
      return material
    } catch {
      if (request === detailRequest) {
        detail.value = null
        detailError.value = t('careerMaterial.loadDetailError')
      }
      return null
    }
  })()
  detailLoad = { id, promise }
  try {
    return await promise
  } finally {
    if (detailLoad?.promise === promise) detailLoad = null
    if (request === detailRequest) detailLoading.value = false
  }
}

async function loadProfile() {
  profileLoading.value = true
  try {
    profile.value = normalizePersonalProfile((await getPersonalProfile()).data.data)
    profileBaseline.value = JSON.stringify(profile.value)
  } catch {
    profileMessage.value = t('careerMaterial.profileLoadError')
  } finally {
    profileLoading.value = false
  }
}

async function loadResumes() {
  if (resumesLoaded) return
  try {
    resumes.value = (await listResumes()).data.data
    resumesLoaded = true
  } catch { resumes.value = [] }
}

async function loadRelationMaterials() {
  if (relationMaterialsLoaded) return
  try {
    const [work, projects] = await Promise.all([listMaterials('WORK_EXPERIENCE'), listMaterials('PROJECT_EXPERIENCE')])
    relationMaterials.value = [...work.data.data, ...projects.data.data]
    relationMaterialsLoaded = true
  } catch { relationMaterials.value = [] }
}

function needsRelationMaterials(type: MaterialType) {
  return ['ACHIEVEMENT', 'LEADERSHIP_EXPERIENCE', 'SKILL_EVIDENCE'].includes(type)
}

function resetMaterialPane() {
  paneEpoch++
  panelMode.value = 'none'
  materialDirty.value = false
  detail.value = null
  detailError.value = ''
  mutationError.value = ''
  detailLoading.value = false
  detailRequest++
  detailLoad = null
}

function restoreProfileDraft() {
  if (activeProfile.value && profileDirty.value) {
    profile.value = normalizePersonalProfile(JSON.parse(profileBaseline.value) as PersonalProfile)
  }
}

function leaveProfile() {
  restoreProfileDraft()
  if (activeProfile.value) profileRequest++
  activeProfile.value = false
}

async function selectType(type: '' | MaterialType) {
  if (!confirmDiscard()) return
  leaveProfile()
  resetMaterialPane()
  await updateQuery({ type: type || null, page: null, selected: null })
}

async function selectMaterial(id: number) {
  if (!confirmDiscard()) return false
  leaveProfile()
  resetMaterialPane()
  await updateQuery({ selected: id })
  return true
}

async function editMaterial(id: number) {
  if (selectedId.value !== id && !(await selectMaterial(id))) return
  if (selectedId.value === id && !confirmDiscard()) return
  const material = await loadDetail(id)
  if (!material || selectedId.value !== id) return
  if (needsRelationMaterials(material.materialType)) void loadRelationMaterials()
  panelMode.value = 'edit'
}

async function openCreate() {
  if (!confirmDiscard()) return
  leaveProfile()
  resetMaterialPane()
  initialCreateType.value = routeType.value || 'WORK_EXPERIENCE'
  panelMode.value = 'create'
  if (needsRelationMaterials(initialCreateType.value)) void loadRelationMaterials()
  await updateQuery({ selected: null })
}

async function closePanel() {
  if (!confirmDiscard()) return
  resetMaterialPane()
  await updateQuery({ selected: null })
}

async function openProfile() {
  if (!confirmDiscard()) return
  resetMaterialPane()
  activeProfile.value = true
  void loadResumes()
  await updateQuery({ selected: null })
}

function closeProfile() {
  if (!confirmDiscard()) return
  leaveProfile()
}

async function saveMaterial(payload: CareerMaterialPayload) {
  const epoch = paneEpoch
  const editingId = panelMode.value === 'edit' ? detail.value?.id : null
  saving.value = true
  mutationError.value = ''
  try {
    const response = editingId
      ? await updateMaterial(editingId, payload)
      : await createMaterial(payload)
    const savedMaterial = response.data.data
    if (relationMaterialsLoaded && ['WORK_EXPERIENCE', 'PROJECT_EXPERIENCE'].includes(payload.materialType)) {
      relationMaterialsLoaded = false
      if (mounted) void loadRelationMaterials()
    }
    if (!mounted) return
    if (epoch !== paneEpoch) {
      await loadWorkspace()
      return
    }
    detail.value = savedMaterial
    materialDirty.value = false
    panelMode.value = 'none'
    await Promise.all([loadWorkspace(), updateQuery({ selected: detail.value.id })])
  } catch {
    if (mounted && epoch === paneEpoch) mutationError.value = t('careerMaterial.saveError')
  } finally {
    saving.value = false
  }
}

async function removeMaterial(id: number) {
  if (deletingIds.has(id)) return
  const discardEdit = materialDirty.value
  if (discardEdit && !confirmDiscard()) return
  if (!window.confirm(t('careerMaterial.deleteConfirm'))) return
  if (discardEdit) {
    resetMaterialPane()
    await updateQuery({ selected: null })
  }
  const epoch = paneEpoch
  const selectedAtStart = selectedId.value
  const index = result.value.items.findIndex(item => item.id === id)
  const deletedType = result.value.items[index]?.materialType ?? detail.value?.materialType
  const neighbor = result.value.items[index + 1] ?? result.value.items[index - 1]
  deletingIds.add(id)
  try {
    await deleteMaterial(id)
    mutationError.value = ''
    if (relationMaterialsLoaded && deletedType && ['WORK_EXPERIENCE', 'PROJECT_EXPERIENCE'].includes(deletedType)) {
      relationMaterialsLoaded = false
      if (mounted) void loadRelationMaterials()
    }
    if (!mounted) return
    await loadWorkspace()
    if (epoch === paneEpoch && selectedId.value === selectedAtStart && selectedAtStart === id) {
      resetMaterialPane()
      await updateQuery({ selected: neighbor?.id ?? null })
    }
  } catch {
    if (mounted && epoch === paneEpoch) mutationError.value = t('careerMaterial.deleteError')
  } finally {
    deletingIds.delete(id)
  }
}

async function saveProfile() {
  profileSaving.value = true
  profileMessage.value = ''
  try {
    profile.value = normalizePersonalProfile((await updatePersonalProfile(profile.value)).data.data)
    profileBaseline.value = JSON.stringify(profile.value)
    profileMessage.value = t('careerMaterial.profileSaved')
  } catch {
    profileMessage.value = t('careerMaterial.profileSaveError')
  } finally {
    profileSaving.value = false
  }
}

async function importProfileSuggestion() {
  if (!importResumeId.value) return
  const request = ++profileRequest
  profileLoading.value = true
  profileMessage.value = ''
  try {
    const suggestion = normalizePersonalProfile((await getPersonalProfileImportSuggestion(importResumeId.value)).data.data)
    if (request === profileRequest && activeProfile.value) {
      profile.value = suggestion
      profileMessage.value = t('careerMaterial.profileImportedHint')
    }
  } catch {
    profileMessage.value = t('careerMaterial.profileImportError')
  } finally {
    profileLoading.value = false
  }
}

function updateSearch(value: string) {
  searchInput.value = value
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    if (searchInput.value.trim() !== routeQueryText.value) {
      void updateQuery({ q: searchInput.value.trim() || null, page: null, selected: null })
    }
  }, 300)
}

function updateUsage(value: string) {
  void updateQuery({ usage: value || null, page: null, selected: null })
}

function updateSort(value: string) {
  void updateQuery({ sort: value === 'updatedAt,desc' ? null : value, page: null })
}

function handleBeforeUnload(event: BeforeUnloadEvent) {
  if (!hasUnsavedChanges.value) return
  event.preventDefault()
  event.returnValue = ''
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && (panelOpen.value || activeProfile.value)) {
    if (activeProfile.value) closeProfile()
    else void closePanel()
  }
}

watch(workspaceQueryKey, () => {
  if (searchInput.value !== routeQueryText.value) searchInput.value = routeQueryText.value
  void loadWorkspace()
}, { immediate: true })

watch(selectedId, id => {
  if (id === null) {
    if (panelMode.value === 'none') detail.value = null
    return
  }
  if (detail.value?.id !== id) void loadDetail(id)
}, { immediate: true })

onMounted(() => {
  void loadProfile()
  window.addEventListener('beforeunload', handleBeforeUnload)
  window.addEventListener('keydown', handleKeydown)
})

onBeforeUnmount(() => {
  mounted = false
  searchRequest++
  detailRequest++
  paneEpoch++
  profileRequest++
  if (searchTimer) clearTimeout(searchTimer)
  window.removeEventListener('beforeunload', handleBeforeUnload)
  window.removeEventListener('keydown', handleKeydown)
})

onBeforeRouteLeave(() => !hasUnsavedChanges.value || window.confirm(t('careerMaterial.unsavedConfirm')))
</script>

<template>
  <section class="workspace-page career-material-page">
    <header class="material-page-heading">
      <div>
        <p class="eyebrow"><Database :size="14" /> {{ t('careerMaterial.eyebrow') }}</p>
        <h1>{{ t('careerMaterial.libraryWorkspaceTitle') }}</h1>
        <p class="page-lead">{{ t('careerMaterial.libraryWorkspaceSubtitle') }}</p>
      </div>
      <button class="btn-neon btn-primary" type="button" @click="openCreate"><Plus :size="16" /> {{ t('careerMaterial.newMaterial') }}</button>
    </header>

    <div class="material-workspace" :class="{ 'panel-open': panelOpen, 'profile-open': activeProfile, 'editor-open': panelMode === 'create' || panelMode === 'edit', 'detail-closed': !panelOpen && !activeProfile }">
      <CareerMaterialNavigation
        :profile="profile"
        :type-counts="result.typeCounts"
        :total="totalMaterials"
        :active-type="routeType"
        :profile-active="activeProfile"
        @select-profile="openProfile"
        @select-type="selectType"
      />

      <CareerProfileEditor
        v-if="activeProfile"
        v-model="profile"
        v-model:import-resume-id="importResumeId"
        class="profile-panel"
        :resumes="resumes"
        :loading="profileLoading"
        :saving="profileSaving"
        :message="profileMessage"
        @save="saveProfile"
        @import="importProfileSuggestion"
        @close="closeProfile"
      />

      <main v-else class="library-pane">
        <div class="library-toolbar">
          <label class="search-control">
            <Search :size="16" />
            <span class="sr-only">{{ t('careerMaterial.searchLabel') }}</span>
            <input :value="searchInput" type="search" :placeholder="t('careerMaterial.searchPlaceholder')" @input="updateSearch(($event.target as HTMLInputElement).value)" />
          </label>
          <button class="new-icon-action" type="button" :title="t('careerMaterial.newMaterial')" :aria-label="t('careerMaterial.newMaterial')" @click="openCreate"><Plus :size="18" /></button>
        </div>
        <div class="filter-toolbar">
          <span class="result-count">{{ t('careerMaterial.resultCount', { count: result.totalElements }) }}</span>
          <label><SlidersHorizontal :size="14" /><span class="sr-only">{{ t('careerMaterial.usageLabel') }}</span><select :value="routeUsage" @change="updateUsage(($event.target as HTMLSelectElement).value)"><option value="">{{ t('careerMaterial.usageAll') }}</option><option v-for="option in USAGE_OPTIONS" :key="option.value" :value="option.value">{{ t(option.key) }}</option></select></label>
          <label><span class="sr-only">{{ t('careerMaterial.sortLabel') }}</span><select :value="routeSort" @change="updateSort(($event.target as HTMLSelectElement).value)"><option v-for="option in SORT_OPTIONS" :key="option.value" :value="option.value">{{ t(option.key) }}</option></select></label>
        </div>
        <CareerMaterialList
          :result="result"
          :loading="loading"
          :error="workspaceError"
          :selected-id="selectedId"
          @select="selectMaterial"
          @edit="editMaterial"
          @delete="removeMaterial"
          @retry="loadWorkspace"
          @page="page => updateQuery({ page: page + 1, selected: null })"
        />
      </main>

      <aside v-if="!activeProfile && panelOpen" class="detail-pane" :class="{ 'has-error': detailError || mutationError }" :aria-label="t('careerMaterial.detailTitle')">
        <p v-if="detailError || mutationError" class="pane-error" role="alert">{{ mutationError || detailError }}</p>
        <CareerMaterialForm
          v-if="panelMode === 'create' || panelMode === 'edit'"
          :material="materialForForm"
          :initial-type="initialCreateType"
          :relation-materials="relationMaterials"
          :saving="saving"
          @save="saveMaterial"
          @cancel="closePanel"
          @dirty-change="materialDirty = $event"
          @type-change="type => needsRelationMaterials(type) && loadRelationMaterials()"
        />
        <CareerMaterialDetail
          v-else
          :material="detail"
          :loading="detailLoading"
          @edit="detail && editMaterial(detail.id)"
          @delete="detail && removeMaterial(detail.id)"
          @close="closePanel"
        />
      </aside>
    </div>
  </section>
</template>

<style scoped>
.career-material-page { width: 100%; max-width: none; min-width: 0; grid-template-columns: minmax(0, 1fr); gap: 16px; overflow-x: clip; }
.material-page-heading { display: flex; width: 100%; min-width: 0; align-items: end; justify-content: space-between; gap: 24px; }
.material-page-heading > div { min-width: 0; }
.material-page-heading h1 { margin: 4px 0 5px; font-family: var(--font-display); font-size: 28px; letter-spacing: 0; }
.material-page-heading h1 { max-width: 100%; overflow-wrap: anywhere; }
.material-page-heading > .btn-primary { flex: none; }
.material-page-heading .page-lead { max-width: 680px; font-size: 12px; }
.material-workspace { display: grid; width: 100%; max-width: 100%; grid-template-columns: 220px minmax(400px, 1fr) minmax(360px, 420px); height: calc(100dvh - 174px); min-height: 520px; max-height: 820px; overflow: hidden; border: 1px solid var(--border); border-radius: 7px; background: var(--bg-surface); box-shadow: var(--shadow-sm); }
.material-workspace.detail-closed { grid-template-columns: 220px minmax(0, 1fr); }
.material-workspace.editor-open { grid-template-columns: 220px minmax(400px, 1fr) clamp(460px, 32vw, 520px); }
.library-pane { display: grid; grid-template-rows: auto auto minmax(0, 1fr); min-width: 0; min-height: 0; border-right: 1px solid var(--border); background: var(--bg-surface); }
.library-toolbar { display: grid; grid-template-columns: minmax(0, 1fr) 36px; gap: 8px; padding: 12px 14px 8px; }
.search-control { display: grid; grid-template-columns: 20px minmax(0, 1fr); align-items: center; min-height: 38px; padding: 0 10px; border: 1px solid var(--border); border-radius: 6px; color: var(--text-tertiary); background: var(--bg-input); }
.search-control:focus-within { border-color: var(--border-focus); box-shadow: 0 0 0 3px var(--accent-light); color: var(--accent); }
.search-control input { min-width: 0; border: 0; outline: 0; color: var(--text-primary); background: transparent; font: inherit; font-size: 12px; }
.new-icon-action { display: grid; width: 36px; height: 36px; place-items: center; padding: 0; border: 1px solid var(--accent); border-radius: 6px; color: #fff; background: var(--accent); cursor: pointer; }
.new-icon-action:hover { filter: brightness(.94); }
.filter-toolbar { display: flex; min-height: 42px; align-items: center; gap: 8px; padding: 0 14px 8px; border-bottom: 1px solid var(--border); }
.filter-toolbar .result-count { margin-right: auto; color: var(--text-tertiary); font-size: 10px; white-space: nowrap; }
.filter-toolbar label { display: flex; align-items: center; gap: 4px; color: var(--accent); }
.filter-toolbar select { max-width: 140px; min-height: 30px; padding: 4px 24px 4px 7px; border: 1px solid var(--border); border-radius: 5px; color: var(--text-secondary); background: var(--bg-input); font: inherit; font-size: 10px; }
.detail-pane { min-width: 0; min-height: 0; }
.detail-pane.has-error { display: grid; grid-template-rows: auto minmax(0, 1fr); }
.pane-error { margin: 0; padding: 9px 14px; border-bottom: 1px solid color-mix(in srgb, var(--danger) 25%, var(--border)); color: var(--danger); background: var(--danger-light); font-size: 11px; line-height: 1.5; }
.profile-panel { grid-column: 2 / 4; }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; }
@media (max-width: 1179px) {
  .material-workspace { grid-template-columns: 210px minmax(0, 1fr); }
  .material-workspace.editor-open { grid-template-columns: 210px minmax(0, 1fr); }
  .detail-pane { position: fixed; z-index: 40; top: 64px; right: 0; bottom: 0; width: min(440px, calc(100vw - 32px)); border-left: 1px solid var(--border); box-shadow: -16px 0 38px rgb(10 34 27 / .16); transform: translateX(105%); transition: transform .2s ease; background: var(--bg-surface); }
  .panel-open .detail-pane { transform: translateX(0); }
  .profile-panel { grid-column: 2; }
}
@media (max-width: 767px) {
  .career-material-page { gap: 12px; }
  .material-page-heading { align-items: stretch; }
  .material-page-heading h1 { font-size: 24px; }
  .material-page-heading .page-lead { display: none; }
  .material-page-heading > .btn-primary { align-self: end; width: 38px; height: 38px; padding: 0; font-size: 0; }
  .material-workspace { display: block; height: auto; min-height: 0; max-height: none; overflow: visible; }
  .library-pane { display: block; border-right: 0; }
  .library-toolbar { position: sticky; z-index: 4; top: 52px; background: var(--bg-surface); }
  .filter-toolbar { position: sticky; z-index: 4; top: 102px; background: var(--bg-surface); }
  .detail-pane, .profile-panel { position: fixed; z-index: 50; inset: 0; display: none; width: 100%; overflow-y: auto; border: 0; background: var(--bg-surface); transform: none; }
  .panel-open .detail-pane, .profile-open .profile-panel { display: block; }
}
@media (prefers-reduced-motion: reduce) { .detail-pane { transition: none; } }
</style>
