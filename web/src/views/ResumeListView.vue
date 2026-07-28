<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ArrowRight, FilePlus2, FileText, FolderOpen, Trash2, Upload } from 'lucide-vue-next'
import { useResumeStore } from '@/stores/resume'
import { createResume, deleteResume } from '@/api/resume'
import { useLocale } from '@/i18n'

const { locale, t } = useLocale()
const store = useResumeStore()
const title = ref('')
const name = ref('')
const saving = ref(false)
const error = ref('')

onMounted(() => store.load())

function formatDate(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat(locale.value, { year: 'numeric', month: 'short', day: 'numeric' }).format(date)
}

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
  <section class="workspace-page resume-library-page">
    <header class="library-heading">
      <div>
        <p class="eyebrow"><FolderOpen :size="14" /> {{ t('resumeList.eyebrow') }}</p>
        <h1>{{ t('resumeList.title') }}</h1>
        <p class="page-lead">{{ t('resumeList.subtitle') }}</p>
      </div>
      <RouterLink class="btn-neon btn-secondary" :to="{ name: 'resume-import' }">
        <Upload :size="16" /> {{ t('resumeList.importAction') }}
      </RouterLink>
    </header>

    <section class="resume-start-panel" aria-labelledby="resume-start-title">
      <div class="section-intro">
        <span><FilePlus2 :size="19" /></span>
        <div>
          <p class="section-kicker">{{ t('resumeList.createEyebrow') }}</p>
          <h2 id="resume-start-title">{{ t('resumeList.createTitle') }}</h2>
          <p>{{ t('resumeList.createDescription') }}</p>
        </div>
      </div>
      <form class="resume-create-form" @submit.prevent="create">
        <label>{{ t('resumeList.nameLabel') }}<input v-model.trim="title" required maxlength="255" :placeholder="t('resumeList.namePlaceholder')" /></label>
        <label>{{ t('resumeList.nameField') }}<input v-model.trim="name" required autocomplete="name" :placeholder="t('resumeList.nameFieldPlaceholder')" /></label>
        <button class="btn-neon btn-primary" :disabled="saving">
          {{ saving ? t('resumeList.creating') : t('resumeList.create') }} <ArrowRight v-if="!saving" :size="16" />
        </button>
      </form>
    </section>

    <p v-if="error" class="form-error" role="alert">{{ error }}</p>

    <section class="resume-collection" aria-labelledby="resume-collection-title">
      <header class="collection-heading">
        <div>
          <p class="section-kicker">{{ t('resumeList.collectionEyebrow') }}</p>
          <h2 id="resume-collection-title">{{ t('resumeList.collectionTitle') }}</h2>
        </div>
        <span v-if="!store.loading" class="collection-count">{{ store.items.length }}</span>
      </header>

      <p v-if="store.loading" class="library-loading" role="status">{{ t('resumeList.loading') }}</p>
      <div v-else-if="!store.items.length" class="resume-empty-state">
        <FileText :size="24" />
        <div><h3>{{ t('resumeList.emptyTitle') }}</h3><p>{{ t('resumeList.empty') }}</p></div>
      </div>
      <div v-else class="resume-list">
        <article v-for="r in store.items" :key="r.id" class="resume-row">
          <RouterLink class="resume-row-main" :to="{ name: 'resume-detail', params: { id: r.id } }">
            <span class="resume-document-icon"><FileText :size="19" /></span>
            <span class="resume-row-copy">
              <strong>{{ r.title }}</strong>
              <small>{{ t('resumeList.lastUpdate') }} · {{ formatDate(r.updatedAt) }}</small>
            </span>
            <ArrowRight class="resume-row-arrow" :size="17" />
          </RouterLink>
          <button class="icon-danger-action" type="button" :title="t('resumeList.deleteAction')" :aria-label="`${t('resumeList.deleteAction')} ${r.title}`" @click="remove(r.id)">
            <Trash2 :size="16" />
          </button>
        </article>
      </div>
    </section>
  </section>
</template>

<style scoped>
.resume-library-page { width: min(100%, 1040px); max-width: 1040px; gap: 28px; }
.library-heading { display: flex; align-items: end; justify-content: space-between; gap: 24px; padding-bottom: 24px; border-bottom: 1px solid var(--border); }
.library-heading h1 { margin: 5px 0 7px; font-family: var(--font-display); font-size: 36px; letter-spacing: 0; }
.library-heading .page-lead { max-width: 620px; }
.resume-start-panel { display: grid; grid-template-columns: minmax(220px, .72fr) minmax(0, 1.28fr); gap: 28px; padding: 26px; border: 1px solid var(--border); border-left: 4px solid var(--accent); border-radius: 7px; background: var(--bg-surface); box-shadow: var(--shadow-sm); }
.section-intro { display: grid; grid-template-columns: 40px minmax(0, 1fr); align-content: start; gap: 12px; }
.section-intro > span { display: grid; width: 40px; height: 40px; place-items: center; border-radius: 6px; color: var(--accent); background: var(--accent-light); }
.section-kicker { margin: 0 0 4px; color: var(--text-tertiary); font-family: var(--font-utility); font-size: 10px; font-weight: 700; }
.section-intro h2, .collection-heading h2 { margin: 0; color: var(--text-primary); font-size: 17px; }
.section-intro p:last-child { margin: 6px 0 0; color: var(--text-secondary); font-size: 12px; line-height: 1.65; }
.resume-create-form { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.resume-create-form label { display: grid; gap: 6px; color: var(--text-secondary); font-size: 12px; font-weight: 650; }
.resume-create-form input { width: 100%; min-height: 42px; padding: 9px 10px; border: 1px solid var(--border); border-radius: 6px; color: var(--text-primary); background: var(--bg-input); }
.resume-create-form input:focus { outline: none; border-color: var(--border-focus); box-shadow: 0 0 0 3px var(--accent-light); }
.resume-create-form .btn-neon { grid-column: 1 / -1; justify-self: start; }
.resume-collection { display: grid; gap: 12px; }
.collection-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.collection-count { display: grid; min-width: 30px; height: 30px; padding: 0 8px; place-items: center; border: 1px solid var(--border); border-radius: 5px; color: var(--accent); background: var(--bg-surface); font-family: var(--font-utility); font-size: 11px; font-weight: 700; }
.library-loading { margin: 0; padding: 24px 0; color: var(--text-secondary); }
.resume-empty-state { display: flex; align-items: center; gap: 14px; padding: 24px; border-block: 1px solid var(--border-soft); color: var(--accent); }
.resume-empty-state h3, .resume-empty-state p { margin: 0; }
.resume-empty-state h3 { color: var(--text-primary); font-size: 14px; }
.resume-empty-state p { margin-top: 4px; color: var(--text-secondary); font-size: 12px; }
.resume-list { display: grid; border-top: 1px solid var(--border); }
.resume-row { display: grid; grid-template-columns: minmax(0, 1fr) 38px; align-items: center; gap: 10px; min-height: 78px; border-bottom: 1px solid var(--border); }
.resume-row-main { display: grid; grid-template-columns: 38px minmax(0, 1fr) 20px; align-items: center; gap: 13px; min-width: 0; padding: 15px 8px; color: inherit; text-decoration: none; }
.resume-row-main:hover { text-decoration: none; }
.resume-document-icon { display: grid; width: 38px; height: 44px; place-items: center; border: 1px solid var(--border); border-radius: 4px; color: var(--accent); background: var(--bg-surface); }
.resume-row-copy { display: grid; min-width: 0; gap: 4px; }
.resume-row-copy strong { overflow: hidden; color: var(--text-primary); font-size: 14px; text-overflow: ellipsis; white-space: nowrap; }
.resume-row-copy small { color: var(--text-tertiary); font-family: var(--font-utility); font-size: 10px; }
.resume-row-arrow { color: var(--text-tertiary); transition: transform .16s ease, color .16s ease; }
.resume-row-main:hover .resume-row-arrow { color: var(--accent); transform: translateX(3px); }
.icon-danger-action { display: grid; width: 34px; height: 34px; place-items: center; padding: 0; border: 1px solid transparent; border-radius: 5px; color: var(--text-tertiary); background: transparent; cursor: pointer; }
.icon-danger-action:hover { border-color: color-mix(in srgb, var(--danger) 28%, transparent); color: var(--danger); background: var(--danger-light); }
@media (prefers-reduced-motion: reduce) { .resume-row-arrow { transition: none; } }
@media (max-width: 760px) { .resume-start-panel { grid-template-columns: 1fr; } }
@media (max-width: 600px) { .library-heading { align-items: stretch; flex-direction: column; } .library-heading h1 { font-size: 30px; } .library-heading .btn-neon { justify-content: center; } .resume-start-panel { gap: 20px; padding: 20px 16px; } .resume-create-form { grid-template-columns: 1fr; } .resume-create-form .btn-neon { grid-column: auto; width: 100%; justify-content: center; } }
</style>
