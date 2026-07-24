<script setup lang="ts">
import { onMounted, ref } from 'vue'
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
  <section class="workspace-page">
    <p class="eyebrow">{{ t('achievementGuidance.eyebrow') }}</p>
    <h1>{{ t('achievementGuidance.title') }}</h1>
    <p>{{ t('achievementGuidance.subtitle') }}</p>
    <form class="workspace-card compact-form" @submit.prevent="guide">
      <label>{{ t('achievementGuidance.resume') }}<select v-model.number="selectedResumeId" :disabled="optionsLoading" @change="loadVersions"><option :value="null" disabled>{{ t('common.selectResume') }}</option><option v-for="resume in resumes" :key="resume.id" :value="resume.id">{{ resume.title }}</option></select></label>
      <label>{{ t('achievementGuidance.version') }}<select v-model="resumeVersionId" :disabled="optionsLoading || !hasVersions"><option value="" disabled>{{ t('common.selectVersion') }}</option><option v-for="version in versions" :key="version.id" :value="String(version.id)">v{{ version.versionNo }} · {{ version.sourceType }}</option></select></label>
      <label>{{ t('achievementGuidance.typeLabel') }}<select v-model="section"><option value="work">{{ t('achievementGuidance.typeWork') }}</option><option value="project">{{ t('achievementGuidance.typeProject') }}</option><option value="skills">{{ t('achievementGuidance.typeSkills') }}</option></select></label>
      <label class="wide-field">{{ t('achievementGuidance.contentLabel') }}<textarea v-model.trim="content" rows="6" maxlength="5000" :placeholder="t('achievementGuidance.contentPlaceholder')" /></label>
      <button class="btn-neon btn-primary" :disabled="loading || optionsLoading">{{ loading ? t('achievementGuidance.loading') : t('achievementGuidance.guideButton') }}</button>
    </form>
    <p v-if="error || optionsError" class="form-error" role="alert">{{ error || optionsError }}</p>
    <article v-if="questions.length" class="workspace-card">
      <h2>{{ t('achievementGuidance.questionTitle') }}</h2>
      <ol><li v-for="(question, index) in questions" :key="question"><p>{{ question }}</p><textarea v-model="answers[index]" rows="3" maxlength="2000" :placeholder="t('achievementGuidance.questionPlaceholder')" /></li></ol>
      <label>{{ t('achievementGuidance.materialTitle') }}<input v-model.trim="materialTitle" maxlength="255" /></label>
      <div class="job-actions"><button class="btn-neon btn-primary" type="button" :disabled="saving" @click="saveAsMaterial">{{ saving ? t('achievementGuidance.saving') : t('achievementGuidance.saveButton') }}</button></div>
      <p v-if="savedMessage" class="disclaimer">{{ savedMessage }}</p>
    </article>
  </section>
</template>
