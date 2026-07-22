<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { guideAchievement } from '@/api/ai'
import { createMaterial, type MaterialType } from '@/api/careerMaterial'
import { useResumeJobOptions } from '@/composables/useResumeJobOptions'

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
  if (!resumeVersionId.value || !content.value.trim()) { error.value = 'Select a resume version and provide the statement to improve.'; return }
  loading.value = true; error.value = ''; questions.value = []; answers.value = []; savedMessage.value = ''
  try { questions.value = (await guideAchievement({ resumeVersionId: Number(resumeVersionId.value), section: section.value, content: content.value.trim() })).data.data.questions; answers.value = questions.value.map(() => ''); materialTitle.value = content.value.trim().slice(0, 80) }
  catch { error.value = 'Unable to generate guidance. Confirm AI consent and try again.' }
  finally { loading.value = false }
}

function materialType(): MaterialType {
  if (section.value === 'project') return 'PROJECT_EXPERIENCE'
  if (section.value === 'skills') return 'SKILL'
  return 'WORK_EXPERIENCE'
}

async function saveAsMaterial() {
  if (!answers.value.some((answer) => answer.trim())) { error.value = 'Add at least one verified answer before saving.'; return }
  saving.value = true; error.value = ''; savedMessage.value = ''
  try {
    await createMaterial({ materialType: materialType(), title: materialTitle.value.trim() || 'Confirmed achievement', sourceText: content.value.trim(), contentJson: { originalStatement: content.value.trim(), section: section.value, resumeVersionId: Number(resumeVersionId.value), guidanceQuestions: questions.value, confirmedAnswers: answers.value } })
    savedMessage.value = 'Saved as a career material. You can review and edit it in the material library.'
  } catch { error.value = 'Unable to save the confirmed material.' }
  finally { saving.value = false }
}
onMounted(() => { void load() })
</script>

<template>
  <section class="workspace-page">
    <p class="eyebrow">Achievement guidance</p><h1>Strengthen Resume Facts</h1><p>Get factual follow-up questions that help turn a responsibility into a measurable achievement. Nothing is written back automatically.</p>
    <form class="workspace-card compact-form" @submit.prevent="guide">
      <label>Resume<select v-model.number="selectedResumeId" :disabled="optionsLoading" @change="loadVersions"><option :value="null" disabled>Select a resume</option><option v-for="resume in resumes" :key="resume.id" :value="resume.id">{{ resume.title }}</option></select></label>
      <label>Version<select v-model="resumeVersionId" :disabled="optionsLoading || !hasVersions"><option value="" disabled>Select a version</option><option v-for="version in versions" :key="version.id" :value="String(version.id)">v{{ version.versionNo }} · {{ version.sourceType }}</option></select></label>
      <label>Section<select v-model="section"><option value="work">Work experience</option><option value="project">Project experience</option><option value="skills">Skills</option></select></label>
      <label class="wide-field">Current statement<textarea v-model.trim="content" rows="6" maxlength="5000" placeholder="Describe the responsibility or achievement using only facts you can verify." /></label>
      <button class="btn-neon btn-primary" :disabled="loading || optionsLoading">{{ loading ? 'Generating...' : 'Get follow-up questions' }}</button>
    </form>
    <p v-if="error || optionsError" class="form-error" role="alert">{{ error || optionsError }}</p>
    <article v-if="questions.length" class="workspace-card"><h2>Questions to answer</h2><ol><li v-for="(question, index) in questions" :key="question"><p>{{ question }}</p><textarea v-model="answers[index]" rows="3" maxlength="2000" placeholder="Enter only details you can verify." /></li></ol><label>Material title<input v-model.trim="materialTitle" maxlength="255" /></label><div class="job-actions"><button class="btn-neon btn-primary" type="button" :disabled="saving" @click="saveAsMaterial">{{ saving ? 'Saving...' : 'Confirm and save as material' }}</button></div><p v-if="savedMessage" class="disclaimer">{{ savedMessage }}</p></article>
  </section>
</template>
