<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useCareerMaterialStore } from '@/stores/careerMaterial'
import { createMaterial, deleteMaterial, updateMaterial, type CareerMaterial, type MaterialType, type UsagePreference } from '@/api/careerMaterial'
import { useLocale } from '@/i18n'

const { t } = useLocale()
const store = useCareerMaterialStore()
const materialType = ref<MaterialType>('WORK_EXPERIENCE')
const title = ref('')
const sourceText = ref('')
const contentJson = ref('')
const usagePreference = ref<UsagePreference>('NORMAL')
const filterType = ref<'' | MaterialType>('')
const editingId = ref<number | null>(null)
const saving = ref(false)
const error = ref('')

const TYPE_OPTIONS: { value: MaterialType; key: string }[] = [
  { value: 'WORK_EXPERIENCE', key: 'careerMaterial.filterWorkExperience' },
  { value: 'PROJECT_EXPERIENCE', key: 'careerMaterial.filterProjectExperience' },
  { value: 'EDUCATION', key: 'careerMaterial.filterEducation' },
  { value: 'SKILL', key: 'careerMaterial.filterSkill' },
  { value: 'CERTIFICATE', key: 'careerMaterial.filterCertificate' },
  { value: 'AWARD', key: 'careerMaterial.filterAward' },
]

const USAGE_OPTIONS: { value: UsagePreference; key: string }[] = [
  { value: 'NORMAL', key: 'careerMaterial.usageNormal' },
  { value: 'PREFERRED', key: 'careerMaterial.usagePreferred' },
  { value: 'EXCLUDED', key: 'careerMaterial.usageExcluded' },
]

onMounted(() => store.load())

function resetForm() {
  editingId.value = null
  materialType.value = 'WORK_EXPERIENCE'
  title.value = ''
  sourceText.value = ''
  contentJson.value = ''
  usagePreference.value = 'NORMAL'
}

function edit(material: CareerMaterial) {
  editingId.value = material.id
  materialType.value = material.materialType
  title.value = material.title
  sourceText.value = material.sourceText ?? ''
  contentJson.value = JSON.stringify(material.contentJson, null, 2)
  usagePreference.value = material.usagePreference
}

async function reload() {
  await store.load(filterType.value || undefined)
}

async function create() {
  let parsedContent: Record<string, unknown>
  try {
    parsedContent = contentJson.value.trim()
      ? JSON.parse(contentJson.value) as Record<string, unknown>
      : { title: title.value, sourceText: sourceText.value }
  } catch {
    error.value = t('careerMaterial.invalidJson')
    return
  }
  saving.value = true
  error.value = ''
  try {
    const payload = { materialType: materialType.value, title: title.value, sourceText: sourceText.value || undefined, usagePreference: usagePreference.value, contentJson: parsedContent }
    if (editingId.value) await updateMaterial(editingId.value, payload)
    else await createMaterial(payload)
    resetForm()
    await reload()
  } catch {
    error.value = t('careerMaterial.saveError')
  } finally {
    saving.value = false
  }
}

async function remove(id: number) {
  if (!window.confirm(t('careerMaterial.deleteConfirm'))) return
  await deleteMaterial(id)
  await reload()
}
</script>

<template>
  <section class="workspace-page">
    <h1>{{ t('careerMaterial.title') }}</h1>
    <label class="workspace-card">{{ t('careerMaterial.filterLabel') }}
      <select v-model="filterType" @change="reload">
        <option value="">{{ t('careerMaterial.filterAll') }}</option>
        <option v-for="opt in TYPE_OPTIONS" :key="opt.value" :value="opt.value">{{ t(opt.key) }}</option>
      </select>
    </label>
    <form class="workspace-card material-form" @submit.prevent="create">
      <label>{{ t('careerMaterial.typeLabel') }}
        <select v-model="materialType">
          <option v-for="opt in TYPE_OPTIONS" :key="opt.value" :value="opt.value">{{ t(opt.key) }}</option>
        </select>
      </label>
      <label>{{ t('careerMaterial.titleLabel') }}
        <input v-model.trim="title" required maxlength="255" :placeholder="t('careerMaterial.titlePlaceholder')" />
      </label>
      <label>{{ t('careerMaterial.usageLabel') }}
        <select v-model="usagePreference">
          <option v-for="opt in USAGE_OPTIONS" :key="opt.value" :value="opt.value">{{ t(opt.key) }}</option>
        </select>
      </label>
      <label class="wide-field">{{ t('careerMaterial.sourceLabel') }}
        <textarea v-model.trim="sourceText" rows="4" :placeholder="t('careerMaterial.sourcePlaceholder')" />
      </label>
      <label class="wide-field">{{ t('careerMaterial.jsonLabel') }}
        <textarea v-model="contentJson" rows="8" spellcheck="false" :placeholder="t('careerMaterial.jsonPlaceholder')" />
      </label>
      <div class="dialog-actions">
        <button v-if="editingId" class="btn-neon btn-ghost" type="button" @click="resetForm">{{ t('careerMaterial.cancelEdit') }}</button>
        <button class="btn-neon btn-primary" :disabled="saving">{{ saving ? t('careerMaterial.saving') : editingId ? t('careerMaterial.saveEdit') : t('careerMaterial.saveNew') }}</button>
      </div>
    </form>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <p v-if="store.loading">{{ t('careerMaterial.loading') }}</p>
    <p v-else-if="!store.items.length" class="empty-state">{{ t('careerMaterial.empty') }}</p>
    <div v-else class="job-list">
      <article v-for="m in store.items" :key="m.id" class="workspace-card job-card">
        <div><h2>{{ m.title }}</h2><p>{{ m.materialType }} · {{ m.usagePreference }}</p></div>
        <div class="job-actions">
          <button class="btn-neon btn-ghost" @click="edit(m)">{{ t('common.edit') }}</button>
          <button class="danger-action" :title="t('careerMaterial.deleteAction')" @click="remove(m.id)">{{ t('common.delete') }}</button>
        </div>
      </article>
    </div>
  </section>
</template>
