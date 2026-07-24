<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useResumeStore } from '@/stores/resume'
import { createResume, deleteResume } from '@/api/resume'
import { useLocale } from '@/i18n'

const { t } = useLocale()
const store = useResumeStore()
const title = ref('')
const name = ref('')
const saving = ref(false)
const error = ref('')

onMounted(() => store.load())

async function create() {
  saving.value = true
  error.value = ''
  try {
    await createResume(title.value, { basics: { name: name.value }, work: [], education: [], skills: [], projects: [] })
    title.value = ''
    name.value = ''
    await store.load()
  } catch {
    error.value = t('resumeList.createError')
  } finally {
    saving.value = false
  }
}

async function remove(id: number) {
  if (!window.confirm(t('resumeList.deleteConfirm'))) return
  await deleteResume(id)
  await store.load()
}
</script>

<template>
  <section class="workspace-page">
    <h1>{{ t('resumeList.title') }}</h1>
    <form class="workspace-card inline-form" @submit.prevent="create">
      <label>{{ t('resumeList.nameLabel') }}<input v-model.trim="title" required maxlength="255" :placeholder="t('resumeList.namePlaceholder')" /></label>
      <label>{{ t('resumeList.nameField') }}<input v-model.trim="name" required :placeholder="t('resumeList.nameFieldPlaceholder')" /></label>
      <button class="btn-neon btn-primary" :disabled="saving">{{ saving ? t('resumeList.creating') : t('resumeList.create') }}</button>
    </form>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <p v-if="store.loading">{{ t('resumeList.loading') }}</p>
    <p v-else-if="!store.items.length" class="empty-state">{{ t('resumeList.empty') }}</p>
    <div v-else class="job-list">
      <article v-for="r in store.items" :key="r.id" class="workspace-card job-card">
        <RouterLink :to="{ name: 'resume-detail', params: { id: r.id } }">
          <h2>{{ r.title }}</h2><p>{{ t('resumeList.lastUpdate') }}：{{ r.updatedAt }}</p>
        </RouterLink>
        <button class="danger-action" :title="t('resumeList.deleteAction')" @click="remove(r.id)">{{ t('common.delete') }}</button>
      </article>
    </div>
  </section>
</template>
