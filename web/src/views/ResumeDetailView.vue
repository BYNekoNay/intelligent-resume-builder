<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getResume, listVersions, setCurrentVersion, updateResumeTitle, type ResumeSummary, type ResumeVersion } from '@/api/resume'
import { listJobs, type JobDescription } from '@/api/jobDescription'
import { scoreMatch } from '@/api/scoring'
import { createExport, type ResumeTemplateCode } from '@/api/export'

const props = defineProps<{ id: string }>()
const resume = ref<ResumeSummary | null>(null)
const versions = ref<ResumeVersion[]>([])
const jobs = ref<JobDescription[]>([])
const jobId = ref<number | null>(null)
const runningAction = ref<number | null>(null)
const editingTitle = ref(false)
const titleDraft = ref('')
const savingTitle = ref(false)
const error = ref('')
const router = useRouter()
const templateNames: Record<ResumeTemplateCode, string> = { classic: '经典', modern: '现代', minimal: '极简' }

function versionTemplate(version: ResumeVersion): ResumeTemplateCode {
  const code = (version.resumeJson.template as { code?: string } | undefined)?.code
  return code === 'modern' || code === 'minimal' ? code : 'classic'
}

async function load() {
  const [resumeResponse, versionResponse, jobResponse] = await Promise.all([
    getResume(Number(props.id)),
    listVersions(Number(props.id)),
    listJobs(),
  ])
  resume.value = resumeResponse.data.data
  titleDraft.value = resume.value.title
  versions.value = versionResponse.data.data
  jobs.value = jobResponse.data.data
  jobId.value = jobs.value[0]?.id ?? null
}

onMounted(async () => {
  try {
    await load()
  } catch {
    error.value = '简历详情无法加载，请返回列表后重试。'
  }
})

function startTitleEdit() {
  titleDraft.value = resume.value?.title ?? ''
  editingTitle.value = true
}

function cancelTitleEdit() {
  titleDraft.value = resume.value?.title ?? ''
  editingTitle.value = false
}

async function saveTitle() {
  if (!titleDraft.value.trim()) {
    error.value = '简历标题不能为空。'
    return
  }
  savingTitle.value = true
  error.value = ''
  try {
    const response = await updateResumeTitle(Number(props.id), titleDraft.value.trim())
    resume.value = response.data.data
    titleDraft.value = resume.value.title
    editingTitle.value = false
  } catch {
    error.value = '简历标题保存失败，请稍后重试。'
  } finally {
    savingTitle.value = false
  }
}

async function makeCurrent(version: ResumeVersion) {
  if (resume.value?.currentVersionId === version.id) return
  if (!window.confirm(`将 v${version.versionNo} 设为当前版本？`)) return
  runningAction.value = version.id
  error.value = ''
  try {
    await setCurrentVersion(Number(props.id), version.id)
    await load()
  } catch {
    error.value = '当前版本切换失败，请稍后重试。'
  } finally {
    runningAction.value = null
  }
}

async function score(version: ResumeVersion) {
  if (!jobId.value) {
    error.value = '请先创建并选择一份 JD。'
    return
  }
  runningAction.value = version.id
  error.value = ''
  try {
    const response = await scoreMatch(version.id, jobId.value)
    await router.push({ name: 'match-result', params: { matchResultId: response.data.data.matchResultId } })
  } catch {
    error.value = '规则覆盖度计算失败，请检查 JD 与简历版本后重试。'
  } finally {
    runningAction.value = null
  }
}

async function exportPdf(version: ResumeVersion) {
  runningAction.value = version.id
  error.value = ''
  try {
    const response = await createExport(version.id, versionTemplate(version))
    await router.push({ name: 'export', params: { exportTaskId: response.data.data.id } })
  } catch {
    error.value = 'PDF 导出任务创建失败，请稍后重试。'
  } finally {
    runningAction.value = null
  }
}
</script>

<template>
  <section class="workspace-page">
    <div class="page-heading">
      <div class="resume-title-block">
        <h1>{{ resume?.title ?? `简历 #${props.id}` }} 历史版本</h1>
        <button v-if="!editingTitle" class="text-link" type="button" @click="startTitleEdit">重命名</button>
      </div>
      <RouterLink class="btn-neon btn-primary" :to="{ name: 'resume-editor', params: { id: props.id } }">编辑并新建版本</RouterLink>
    </div>
    <form v-if="editingTitle" class="workspace-card title-edit-form" @submit.prevent="saveTitle">
      <label>简历标题<input v-model.trim="titleDraft" required maxlength="255" autofocus /></label>
      <div class="job-actions"><button class="btn-neon btn-ghost" type="button" :disabled="savingTitle" @click="cancelTitleEdit">取消</button><button class="btn-neon btn-primary" :disabled="savingTitle">{{ savingTitle ? '正在保存…' : '保存标题' }}</button></div>
    </form>
    <label class="workspace-card">用于规则覆盖度的 JD
      <select v-model="jobId"><option :value="null">请选择 JD</option><option v-for="job in jobs" :key="job.id" :value="job.id">{{ job.title }}</option></select>
    </label>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <p v-if="!versions.length" class="empty-state">当前简历还没有版本。</p>
    <div v-else class="job-list">
      <article v-for="v in versions" :key="v.id" class="workspace-card version-card">
        <div><h2>v{{ v.versionNo }} · {{ v.sourceType }}<span class="template-badge">{{ templateNames[versionTemplate(v)] }}样式</span><span v-if="resume?.currentVersionId === v.id" class="current-version">当前</span></h2><p>{{ v.createdAt }}</p></div>
        <div class="job-actions"><button v-if="resume?.currentVersionId !== v.id" class="btn-neon btn-ghost" :disabled="runningAction !== null" @click="makeCurrent(v)">设为当前</button><button class="btn-neon btn-ghost" :disabled="runningAction !== null" @click="score(v)">查看 JD 规则覆盖度</button><button class="btn-neon btn-primary" :disabled="runningAction !== null" @click="exportPdf(v)">{{ runningAction === v.id ? '正在创建任务…' : '导出 PDF' }}</button></div>
      </article>
    </div>
  </section>
</template>
