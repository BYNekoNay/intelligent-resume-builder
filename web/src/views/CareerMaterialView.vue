<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useCareerMaterialStore } from '@/stores/careerMaterial'
import { createMaterial, deleteMaterial, updateMaterial, type CareerMaterial, type MaterialType, type UsagePreference } from '@/api/careerMaterial'

const store = useCareerMaterialStore()
const materialType = ref<MaterialType>('WORK_EXPERIENCE')
const title = ref('')
const sourceText = ref('')
const contentJson = ref('')
const usagePreference = ref<UsagePreference>('NORMAL')
const filterType = ref<'' | MaterialType>('')
const editingId = ref<number | null>(null)
const saving = ref(false)
const error = ref('')

onMounted(() => store.load())

function resetForm() {
  editingId.value = null
  materialType.value = 'WORK_EXPERIENCE'
  title.value = ''
  sourceText.value = ''
  contentJson.value = ''
  usagePreference.value = 'NORMAL'
}

function edit(material: CareerMaterial) {
  editingId.value = material.id
  materialType.value = material.materialType
  title.value = material.title
  sourceText.value = material.sourceText ?? ''
  contentJson.value = JSON.stringify(material.contentJson, null, 2)
  usagePreference.value = material.usagePreference
}

async function reload() {
  await store.load(filterType.value || undefined)
}

async function create() {
  let parsedContent: Record<string, unknown>
  try {
    parsedContent = contentJson.value.trim()
      ? JSON.parse(contentJson.value) as Record<string, unknown>
      : { title: title.value, sourceText: sourceText.value }
  } catch {
    error.value = '资料 JSON 格式无效。'
    return
  }
  saving.value = true
  error.value = ''
  try {
    const payload = { materialType: materialType.value, title: title.value, sourceText: sourceText.value || undefined, usagePreference: usagePreference.value, contentJson: parsedContent }
    if (editingId.value) await updateMaterial(editingId.value, payload)
    else await createMaterial(payload)
    resetForm()
    await reload()
  } catch {
    error.value = '资料保存失败，请检查必填项后重试。'
  } finally {
    saving.value = false
  }
}

async function remove(id: number) {
  if (!window.confirm('删除资料不会改写已创建版本的历史快照，确定继续吗？')) return
  await deleteMaterial(id)
  await reload()
}
</script>

<template>
  <section class="workspace-page">
    <h1>职业资料</h1>
    <label class="workspace-card">筛选资料类型<select v-model="filterType" @change="reload"><option value="">全部</option><option value="WORK_EXPERIENCE">工作经历</option><option value="PROJECT_EXPERIENCE">项目经历</option><option value="EDUCATION">教育经历</option><option value="SKILL">技能</option><option value="CERTIFICATE">证书</option><option value="AWARD">奖项</option></select></label>
    <form class="workspace-card material-form" @submit.prevent="create">
      <label>资料类型<select v-model="materialType"><option value="WORK_EXPERIENCE">工作经历</option><option value="PROJECT_EXPERIENCE">项目经历</option><option value="EDUCATION">教育经历</option><option value="SKILL">技能</option><option value="CERTIFICATE">证书</option><option value="AWARD">奖项</option></select></label>
      <label>标题<input v-model.trim="title" required maxlength="255" placeholder="例如：订单平台重构" /></label>
      <label>使用偏好<select v-model="usagePreference"><option value="NORMAL">正常</option><option value="PREFERRED">优先使用</option><option value="EXCLUDED">默认不使用</option></select></label>
      <label class="wide-field">来源原文<textarea v-model.trim="sourceText" rows="4" placeholder="只填写可核实的经历、职责、技能或成果" /></label>
      <label class="wide-field">资料 JSON<textarea v-model="contentJson" rows="8" spellcheck="false" placeholder="留空时由标题和来源原文生成" /></label>
      <div class="dialog-actions"><button v-if="editingId" class="btn-neon btn-ghost" type="button" @click="resetForm">取消编辑</button><button class="btn-neon btn-primary" :disabled="saving">{{ saving ? '正在保存…' : editingId ? '保存修改' : '保存资料' }}</button></div>
    </form>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <p v-if="store.loading">加载中...</p>
    <p v-else-if="!store.items.length" class="empty-state">资料库为空。添加真实职业资料后才能生成可追溯草稿。</p>
    <div v-else class="job-list"><article v-for="m in store.items" :key="m.id" class="workspace-card job-card"><div><h2>{{ m.title }}</h2><p>{{ m.materialType }} · {{ m.usagePreference }}</p></div><div class="job-actions"><button class="btn-neon btn-ghost" @click="edit(m)">编辑</button><button class="danger-action" title="删除资料" @click="remove(m.id)">删除</button></div></article></div>
  </section>
</template>
