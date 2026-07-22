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
  if (!resumeVersionId.value || !content.value.trim()) { error.value = '请选择简历版本并填写需要完善的描述。'; return }
  loading.value = true; error.value = ''; questions.value = []; answers.value = []; savedMessage.value = ''
  try { questions.value = (await guideAchievement({ resumeVersionId: Number(resumeVersionId.value), section: section.value, content: content.value.trim() })).data.data.questions; answers.value = questions.value.map(() => ''); materialTitle.value = content.value.trim().slice(0, 80) }
  catch { error.value = '无法生成引导问题，请确认 AI 数据处理同意后重试。' }
  finally { loading.value = false }
}

function materialType(): MaterialType {
  if (section.value === 'project') return 'PROJECT_EXPERIENCE'
  if (section.value === 'skills') return 'SKILL'
  return 'WORK_EXPERIENCE'
}

async function saveAsMaterial() {
  if (!answers.value.some((answer) => answer.trim())) { error.value = '请至少填写一条可核实的回答后再保存。'; return }
  saving.value = true; error.value = ''; savedMessage.value = ''
  try {
    await createMaterial({ materialType: materialType(), title: materialTitle.value.trim() || '已确认的成就', sourceText: content.value.trim(), contentJson: { originalStatement: content.value.trim(), section: section.value, resumeVersionId: Number(resumeVersionId.value), guidanceQuestions: questions.value, confirmedAnswers: answers.value } })
    savedMessage.value = '已保存为职业资料，可在资料库中继续查看和编辑。'
  } catch { error.value = '无法保存已确认的资料。' }
  finally { saving.value = false }
}
onMounted(() => { void load() })
</script>

<template>
  <section class="workspace-page">
    <p class="eyebrow">成就引导</p><h1>强化简历事实</h1><p>通过可核实的追问，将职责描述转化为可衡量的成就；系统不会自动写回任何内容。</p>
    <form class="workspace-card compact-form" @submit.prevent="guide">
      <label>简历<select v-model.number="selectedResumeId" :disabled="optionsLoading" @change="loadVersions"><option :value="null" disabled>选择简历</option><option v-for="resume in resumes" :key="resume.id" :value="resume.id">{{ resume.title }}</option></select></label>
      <label>简历版本<select v-model="resumeVersionId" :disabled="optionsLoading || !hasVersions"><option value="" disabled>选择版本</option><option v-for="version in versions" :key="version.id" :value="String(version.id)">v{{ version.versionNo }} · {{ version.sourceType }}</option></select></label>
      <label>经历类型<select v-model="section"><option value="work">工作经历</option><option value="project">项目经历</option><option value="skills">专业技能</option></select></label>
      <label class="wide-field">当前描述<textarea v-model.trim="content" rows="6" maxlength="5000" placeholder="只描述你能够核实的职责或成果。" /></label>
      <button class="btn-neon btn-primary" :disabled="loading || optionsLoading">{{ loading ? '正在生成...' : '获取追问问题' }}</button>
    </form>
    <p v-if="error || optionsError" class="form-error" role="alert">{{ error || optionsError }}</p>
    <article v-if="questions.length" class="workspace-card"><h2>需要补充的问题</h2><ol><li v-for="(question, index) in questions" :key="question"><p>{{ question }}</p><textarea v-model="answers[index]" rows="3" maxlength="2000" placeholder="只填写能够核实的细节。" /></li></ol><label>资料标题<input v-model.trim="materialTitle" maxlength="255" /></label><div class="job-actions"><button class="btn-neon btn-primary" type="button" :disabled="saving" @click="saveAsMaterial">{{ saving ? '正在保存...' : '确认并保存为资料' }}</button></div><p v-if="savedMessage" class="disclaimer">{{ savedMessage }}</p></article>
  </section>
</template>
