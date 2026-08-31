<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { CheckCircle2, Lightbulb, Save, SearchCheck } from 'lucide-vue-next'
import { guideAchievement } from '@/api/ai'
import { createMaterial, type MaterialType } from '@/api/careerMaterial'
import { useResumeJobOptions } from '@/composables/useResumeJobOptions'
import { useLocale } from '@/i18n'

const { t } = useLocale()
const resumeVersionId = ref('')
const section = ref('work')
const content = ref('')
const questions = ref<string[]>([])
const answers = ref<string[]>([])
const materialTitle = ref('')
const loading = ref(false)
const saving = ref(false)
const savedMessage = ref('')
const error = ref('')
const { resumes, versions, selectedResumeId, loading: optionsLoading, error: optionsError, hasVersions, load, loadVersions } = useResumeJobOptions()

async function guide() {
  if (!resumeVersionId.value || !content.value.trim()) { error.value = t('achievementGuidance.errorNoInput'); return }
  loading.value = true; error.value = ''; questions.value = []; answers.value = []; savedMessage.value = ''
  try { questions.value = (await guideAchievement({ resumeVersionId: Number(resumeVersionId.value), section: section.value, content: content.value.trim() })).data.data.questions; answers.value = questions.value.map(() => ''); materialTitle.value = content.value.trim().slice(0, 80) }
  catch { error.value = t('achievementGuidance.errorGenerate') }
  finally { loading.value = false }
}

function materialType(): MaterialType {
  if (section.value === 'project') return 'PROJECT_EXPERIENCE'
  if (section.value === 'skills') return 'SKILL'
  return 'WORK_EXPERIENCE'
}

async function saveAsMaterial() {
  if (!answers.value.some((answer) => answer.trim())) { error.value = t('achievementGuidance.errorEmptyAnswer'); return }
  saving.value = true; error.value = ''; savedMessage.value = ''
  try {
    await createMaterial({ materialType: materialType(), title: materialTitle.value.trim() || 'Confirmed achievement', sourceText: content.value.trim(), contentJson: { originalStatement: content.value.trim(), section: section.value, resumeVersionId: Number(resumeVersionId.value), guidanceQuestions: questions.value, confirmedAnswers: answers.value } })
    savedMessage.value = t('achievementGuidance.saveSuccess')
  } catch { error.value = t('achievementGuidance.errorSave') }
  finally { saving.value = false }
}
onMounted(() => { void load() })
</script>

<template>
  <section class="workspace-page achievement-page">
    <header class="achievement-heading"><p class="eyebrow"><Lightbulb :size="14" />{{ t('achievementGuidance.eyebrow') }}</p><h1>{{ t('achievementGuidance.title') }}</h1><p class="page-lead">{{ t('achievementGuidance.subtitle') }}</p></header>
    <form class="workspace-card compact-form achievement-source" @submit.prevent="guide">
      <header class="achievement-section-heading"><span><SearchCheck :size="19" /></span><div><p>{{ t('achievementGuidance.sourceEyebrow') }}</p><h2>{{ t('achievementGuidance.sourceTitle') }}</h2><small>{{ t('achievementGuidance.sourceDescription') }}</small></div></header>
      <label>{{ t('achievementGuidance.resume') }}<select v-model.number="selectedResumeId" :disabled="optionsLoading" @change="loadVersions"><option :value="null" disabled>{{ t('common.selectResume') }}</option><option v-for="resume in resumes" :key="resume.id" :value="resume.id">{{ resume.title }}</option></select></label>
      <label>{{ t('achievementGuidance.version') }}<select v-model="resumeVersionId" :disabled="optionsLoading || !hasVersions"><option value="" disabled>{{ t('common.selectVersion') }}</option><option v-for="version in versions" :key="version.id" :value="String(version.id)">v{{ version.versionNo }} · {{ version.sourceType }}</option></select></label>
      <label>{{ t('achievementGuidance.typeLabel') }}<select v-model="section"><option value="work">{{ t('achievementGuidance.typeWork') }}</option><option value="project">{{ t('achievementGuidance.typeProject') }}</option><option value="skills">{{ t('achievementGuidance.typeSkills') }}</option></select></label>
      <label class="wide-field">{{ t('achievementGuidance.contentLabel') }}<textarea v-model.trim="content" rows="6" maxlength="5000" :placeholder="t('achievementGuidance.contentPlaceholder')" /></label>
      <button class="btn-neon btn-primary" :disabled="loading || optionsLoading"><SearchCheck :size="16" />{{ loading ? t('achievementGuidance.loading') : t('achievementGuidance.guideButton') }}</button>
    </form>
    <p v-if="error || optionsError" class="form-error" role="alert">{{ error || optionsError }}</p>
    <article v-if="questions.length" class="workspace-card guidance-result">
      <header><div><p class="section-kicker">{{ t('achievementGuidance.reviewEyebrow') }}</p><h2>{{ t('achievementGuidance.questionTitle') }}</h2></div><span>{{ questions.length }}</span></header>
      <ol><li v-for="(question, index) in questions" :key="question"><b>{{ index + 1 }}</b><div><p>{{ question }}</p><textarea v-model="answers[index]" rows="3" maxlength="2000" :placeholder="t('achievementGuidance.questionPlaceholder')" /></div></li></ol>
      <label>{{ t('achievementGuidance.materialTitle') }}<input v-model.trim="materialTitle" maxlength="255" /></label>
      <div class="job-actions"><button class="btn-neon btn-primary" type="button" :disabled="saving" @click="saveAsMaterial"><Save :size="16" />{{ saving ? t('achievementGuidance.saving') : t('achievementGuidance.saveButton') }}</button></div>
      <p v-if="savedMessage" class="saved-guidance"><CheckCircle2 :size="14" />{{ savedMessage }}</p>
    </article>
  </section>
</template>

<style scoped>
.achievement-page { width: min(100%, 920px); max-width: 920px; gap: 24px; }.achievement-heading { padding-bottom: 22px; border-bottom: 1px solid var(--border); }.achievement-heading h1 { margin: 5px 0 7px; font-family: var(--font-display); font-size: 34px; letter-spacing: 0; }.achievement-heading .page-lead { max-width: 650px; font-size: 12px; }.achievement-source { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; padding: 24px; border-left: 4px solid var(--highlight); }.achievement-section-heading { grid-column: 1 / -1; display: grid; grid-template-columns: 40px 1fr; gap: 12px; padding-bottom: 18px; border-bottom: 1px solid var(--border-soft); }.achievement-section-heading > span { display: grid; width: 40px; height: 40px; place-items: center; border-radius: 6px; color: var(--accent); background: var(--accent-light); }.achievement-section-heading p, .achievement-section-heading h2, .achievement-section-heading small { display: block; margin: 0; }.achievement-section-heading p, .section-kicker { color: var(--text-tertiary); font-family: var(--font-utility); font-size: 9px; font-weight: 700; }.achievement-section-heading h2 { margin-top: 3px; font-size: 16px; }.achievement-section-heading small { margin-top: 5px; color: var(--text-secondary); font-size: 10px; }.achievement-source .wide-field, .achievement-source .btn-neon { grid-column: 1 / -1; }.guidance-result > header { display: flex; align-items: center; justify-content: space-between; padding-bottom: 14px; border-bottom: 1px solid var(--border-soft); }.guidance-result h2 { margin: 3px 0 0; font-size: 16px; }.guidance-result > header span { color: var(--accent); font-family: var(--font-utility); font-size: 24px; font-weight: 700; }.guidance-result ol { display: grid; gap: 0; margin: 0 0 18px; padding: 0; list-style: none; }.guidance-result li { display: grid; grid-template-columns: 28px 1fr; gap: 12px; padding: 18px 0; border-bottom: 1px solid var(--border-soft); }.guidance-result li > b { display: grid; width: 26px; height: 26px; place-items: center; border-radius: 50%; color: var(--accent); background: var(--accent-light); font-family: var(--font-utility); font-size: 9px; }.guidance-result li p { margin: 3px 0 9px; color: var(--text-primary); font-size: 11px; font-weight: 650; }.saved-guidance { display: flex; align-items: center; gap: 6px; color: var(--success); font-size: 10px; }
@media (max-width: 680px) { .achievement-heading h1 { font-size: 29px; }.achievement-source { grid-template-columns: 1fr; padding: 20px 16px; }.achievement-section-heading, .achievement-source .wide-field, .achievement-source .btn-neon { grid-column: auto; }.achievement-source .btn-neon { width: 100%; justify-content: center; } }
</style>
