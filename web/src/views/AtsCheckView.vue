<script setup lang="ts">
import { ref } from 'vue'
import { runAtsCheck, type AtsCheckResponse } from '@/api/ats'

const resumeVersionId = ref('')
const jobDescriptionId = ref('')
const result = ref<AtsCheckResponse | null>(null)
const error = ref('')
const loading = ref(false)

async function check() {
  error.value = ''
  result.value = null
  const resumeId = Number(resumeVersionId.value)
  const jobId = Number(jobDescriptionId.value)
  if (!resumeId || !jobId) { error.value = '请输入简历版本 ID 和 JD ID。'; return }
  loading.value = true
  try { result.value = (await runAtsCheck(resumeId, jobId)).data.data } catch { error.value = '体检失败，请确认资源归属和网络状态。' } finally { loading.value = false }
}
</script>

<template>
  <section class="workspace-page">
    <p class="eyebrow">Resume health</p><h1>ATS 规则体检</h1>
    <p class="disclaimer">这是规则化简历体检，不是企业 ATS 结果，也不代表录用概率。</p>
    <form class="workspace-card compact-form" @submit.prevent="check">
      <label>简历版本 ID<input v-model="resumeVersionId" inputmode="numeric" placeholder="例如：12" /></label>
      <label>目标 JD ID<input v-model="jobDescriptionId" inputmode="numeric" placeholder="例如：4" /></label>
      <button class="btn-neon btn-primary" :disabled="loading">{{ loading ? '检查中…' : '开始体检' }}</button>
    </form>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <article v-if="result" class="workspace-card">
      <div class="score-grid"><p><strong>体检分</strong>{{ result.totalScore }}</p><p><strong>结构</strong>{{ result.checks.structure }}</p><p><strong>关键词覆盖</strong>{{ result.checks.keywordCoverage }}</p></div>
      <h2>风险与依据</h2><ul><li v-for="risk in result.risks" :key="risk">{{ risk }}</li><li v-if="!result.risks.length">未发现明显结构风险。</li></ul>
      <small class="disclaimer">{{ result.disclaimer }}</small>
    </article>
  </section>
</template>
