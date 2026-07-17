<script setup lang="ts">
import { ref } from 'vue'
import { generateResumeFromMaterial, type MaterialGenerationResponse } from '@/api/materialGeneration'
const raw = ref(''); const result = ref<MaterialGenerationResponse | null>(null); const error = ref(''); const loading = ref(false)
async function generate() { error.value=''; result.value=null; if(!raw.value.trim()){error.value='请先粘贴零碎资料。';return};loading.value=true;try{result.value=(await generateResumeFromMaterial(raw.value)).data.data}catch{error.value='生成失败，请确认 AI 授权和网络状态。'}finally{loading.value=false} }
</script>
<template><section class="workspace-page"><p class="eyebrow">Material to resume</p><h1>零碎资料生成简历</h1><p>保留原始素材，只生成可人工编辑的 JSON Resume 草稿，不自动创建版本。</p><form class="workspace-card" @submit.prevent="generate"><label>原始资料<textarea v-model="raw" rows="12" maxlength="30000" placeholder="粘贴个人背景、工作经历、项目、技术栈和求职方向…" /></label><button class="btn-neon btn-primary" :disabled="loading">{{loading?'生成中…':'生成结构化草稿'}}</button></form><p v-if="error" class="form-error" role="alert">{{error}}</p><article v-if="result" class="workspace-card"><p class="disclaimer">草稿需要人工确认后再写入简历版本。</p><h2>后续建议</h2><ul><li v-for="suggestion in result.suggestions" :key="suggestion">{{suggestion}}</li></ul><details><summary>查看结构化草稿</summary><pre>{{ JSON.stringify(result.generatedResumeJson, null, 2) }}</pre></details></article></section></template>
