<script setup lang="ts">
import{onMounted,ref}from'vue';import{createInterviewAsset,listInterviewAssets,type InterviewAsset}from'@/api/interviewAsset'
const assets=ref<InterviewAsset[]>([]);const question=ref('');const original=ref('');const suggestion=ref('');const error=ref('')
async function load(){try{assets.value=(await listInterviewAssets()).data.data}catch{error.value='答案资产无法加载。'}}
async function save(){try{const item=(await createInterviewAsset({questionText:question.value,originalAnswerText:original.value,suggestedAnswerText:suggestion.value||undefined})).data.data;assets.value.unshift(item);question.value='';original.value='';suggestion.value=''}catch{error.value='保存失败，请检查必填内容。'}}
onMounted(()=>{void load()})
</script>
<template><section class="workspace-page"><p class="eyebrow">Answer library</p><h1>面试答案资产</h1><p>原始回答与 AI 建议始终分开保存，便于复盘真实表现。</p><form class="workspace-card" @submit.prevent="save"><label>问题<textarea v-model="question" rows="2" required /></label><label>原始回答<textarea v-model="original" rows="5" required /></label><label>建议回答<textarea v-model="suggestion" rows="5" /></label><button class="btn-neon btn-primary">保存资产</button></form><p v-if="error" class="form-error">{{error}}</p><div class="workspace-list"><article v-for="asset in assets" :key="asset.id" class="workspace-card"><h2>{{asset.questionText}}</h2><h3>原始回答</h3><p>{{asset.originalAnswerText}}</p><template v-if="asset.suggestedAnswerText"><h3>AI 建议</h3><p>{{asset.suggestedAnswerText}}</p></template></article></div></section></template>
