<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useJobDescriptionStore } from '@/stores/jobDescription'
import { useResumeStore } from '@/stores/resume'
import { useCareerMaterialStore } from '@/stores/careerMaterial'
import { useAiTaskStore } from '@/stores/aiTask'
import { generateForJob, getConsent } from '@/api/ai'
import { createJob, deleteJob, parseJob, type JobDescription } from '@/api/jobDescription'

const store = useJobDescriptionStore()
const resumeStore = useResumeStore()
const materialStore = useCareerMaterialStore()
const taskStore = useAiTaskStore()
const router = useRouter()
const route = useRoute()
const targetResumeId = ref<number | null>(null)
const preferences = ref<Record<number, 'included' | 'preferred' | 'excluded'>>({})
const creatingFor = ref<number | null>(null)
const error = ref('')
const title = ref('')
const companyName = ref('')
const jdText = ref('')
const saving = ref(false)
const parsedResult = ref<JobDescription | null>(null)

onMounted(async () => {
  await Promise.all([store.load(), resumeStore.load(), materialStore.load()])
  targetResumeId.value = resumeStore.items[0]?.id ?? null
})

function preferenceIds(preference: 'included' | 'preferred' | 'excluded') {
  return Object.entries(preferences.value)
    .filter(([, value]) => value === preference)
    .map(([id]) => Number(id))
}

async function generate(jobId: number) {
  if (!targetResumeId.value) {
    error.value = '请先创建并选择一份目标简历。'
    return
  }
  creatingFor.value = jobId
  error.value = ''
  try {
    const consent = (await getConsent()).data.data
    if (consent?.eventType !== 'GRANTED') {
      await router.push({ name: 'ai-consent', query: { redirect: route.fullPath } })
      return
    }
    const response = await generateForJob({
      targetResumeId: targetResumeId.value,
      jobDescriptionId: jobId,
      includedMaterialIds: preferenceIds('included'),
      preferredMaterialIds: preferenceIds('preferred'),
      excludedMaterialIds: preferenceIds('excluded'),
    }, `job-generation-${jobId}-${Date.now()}`)
    taskStore.remember(response.data.data.id)
    await router.push({ name: 'job-generation-confirm', params: { jobId }, query: { taskId: response.data.data.id } })
  } catch {
    error.value = '未能创建岗位定制任务。请先确认已授权 AI 数据处理，并检查资料和 JD。'
  } finally {
    creatingFor.value = null
  }
}

async function create() {
  saving.value = true
  error.value = ''
  try {
    await createJob({ title: title.value, companyName: companyName.value || undefined, jdText: jdText.value })
    title.value = ''
    companyName.value = ''
    jdText.value = ''
    await store.load()
  } catch {
    error.value = 'JD 保存失败，请检查岗位名称和描述长度。'
  } finally {
    saving.value = false
  }
}

async function parse(id: number) {
  try {
    parsedResult.value = (await parseJob(id)).data.data
    await store.load()
  } catch {
    error.value = 'JD 解析失败，请稍后重试。'
  }
}

async function remove(id: number) {
  if (!window.confirm('删除 JD 后无法继续基于它生成草稿，确定继续吗？')) return
  await deleteJob(id)
  await store.load()
}
</script>

<template>
  <section class="workspace-page">
    <h1>岗位 JD</h1>
    <p class="page-lead">选择目标简历，并按资料的重要性指定“固定使用 / 优先使用 / 不使用”。生成后仍需逐项确认。</p>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>

    <form class="workspace-card job-form" @submit.prevent="create">
      <label>岗位名称<input v-model.trim="title" required maxlength="255" placeholder="例如：高级 Java 工程师" /></label>
      <label>公司名称<input v-model.trim="companyName" maxlength="255" placeholder="可选" /></label>
      <label class="wide-field">JD 原文<textarea v-model.trim="jdText" required maxlength="5000" rows="6" placeholder="粘贴岗位职责、要求和技能关键词" /><small class="field-count">{{ jdText.length }}/5000</small></label>
      <button class="btn-neon btn-primary" :disabled="saving">{{ saving ? '正在保存…' : '保存 JD' }}</button>
    </form>

    <div v-if="parsedResult" class="workspace-card"><h2>{{ parsedResult.title }} · 解析结果</h2><pre class="json-preview">{{ JSON.stringify(parsedResult.parsedKeywordsJson, null, 2) }}</pre></div>

    <div class="generation-config workspace-card">
      <label>目标简历
        <select v-model="targetResumeId">
          <option :value="null">请选择简历</option>
          <option v-for="resume in resumeStore.items" :key="resume.id" :value="resume.id">{{ resume.title }}</option>
        </select>
      </label>
      <RouterLink class="text-link" to="/ai-consent">管理 AI 数据处理授权</RouterLink>
      <div v-if="materialStore.items.length" class="material-preferences">
        <h2>资料使用偏好</h2>
        <label v-for="material in materialStore.items" :key="material.id" class="preference-row">
          <span><strong>{{ material.title }}</strong><small>{{ material.materialType }}</small></span>
          <select v-model="preferences[material.id]">
            <option :value="undefined">遵循资料默认设置</option>
            <option value="included">固定使用</option>
            <option value="preferred">优先使用</option>
            <option value="excluded">不使用</option>
          </select>
        </label>
      </div>
    </div>

    <p v-if="store.loading">正在加载 JD…</p>
    <p v-else-if="!store.items.length" class="empty-state">暂无 JD。请先创建岗位描述。</p>
    <div v-else class="job-list">
      <article v-for="job in store.items" :key="job.id" class="workspace-card job-card">
        <div><h2>{{ job.title }}</h2><p>{{ job.companyName || '未填写公司' }} · {{ job.parsedAt ? '已解析关键词' : '尚未解析' }}</p></div>
        <div class="job-actions"><button class="btn-neon btn-ghost" @click="parse(job.id)">解析 JD</button><button class="btn-neon btn-primary" :disabled="creatingFor !== null" @click="generate(job.id)">{{ creatingFor === job.id ? '正在创建任务…' : '生成定制草稿' }}</button><button class="danger-action" title="删除 JD" @click="remove(job.id)">删除</button></div>
      </article>
    </div>
  </section>
</template>
