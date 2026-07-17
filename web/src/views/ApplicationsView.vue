<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { createApplication, listApplications, updateApplicationStatus, type ApplicationRecord, type ApplicationStatus } from '@/api/application'

const records = ref<ApplicationRecord[]>([])
const loading = ref(true)
const error = ref('')
const jobDescriptionId = ref('')
const resumeVersionId = ref('')
const coverLetterText = ref('')
const openingMessageText = ref('')
const statuses: ApplicationStatus[] = ['DRAFT', 'APPLIED', 'INTERVIEWING', 'OFFERED', 'REJECTED', 'WITHDRAWN']
async function load() { try { records.value = (await listApplications()).data.data } catch { error.value = '投递记录无法加载。' } finally { loading.value = false } }
const feedbackDraft = ref<Record<number, string>>({})
async function changeStatus(record: ApplicationRecord, status: ApplicationStatus) { try { const response = await updateApplicationStatus(record.id, status, feedbackDraft.value[record.id] ?? record.feedbackText ?? undefined); Object.assign(record, response.data.data); feedbackDraft.value[record.id] = record.feedbackText ?? '' } catch { error.value = '状态更新失败，请稍后重试。' } }
async function saveFeedback(record: ApplicationRecord) { await changeStatus(record, record.status) }
async function create() { try { const response = await createApplication({ jobDescriptionId: Number(jobDescriptionId.value), resumeVersionId: Number(resumeVersionId.value), status: 'DRAFT', coverLetterText: coverLetterText.value || undefined, openingMessageText: openingMessageText.value || undefined }); records.value.unshift(response.data.data); jobDescriptionId.value=''; resumeVersionId.value=''; coverLetterText.value=''; openingMessageText.value='' } catch { error.value='创建投递记录失败，请确认 JD 与简历版本归属。' } }
onMounted(() => { void load() })
</script>

<template>
  <section class="workspace-page"><p class="eyebrow">Application tracker</p><h1>投递管理</h1><p>只记录你主动维护的投递，不自动发送、不抓取岗位。</p>
    <form class="workspace-card compact-form" @submit.prevent="create"><label>JD ID<input v-model="jobDescriptionId" inputmode="numeric" required /></label><label>简历版本 ID<input v-model="resumeVersionId" inputmode="numeric" required /></label><label>求职信<textarea v-model="coverLetterText" rows="4" /></label><label>开场白<textarea v-model="openingMessageText" rows="4" /></label><button class="btn-neon btn-primary">创建草稿记录</button></form>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p><p v-if="loading">正在加载投递记录…</p>
    <div v-else-if="records.length" class="workspace-list"><article v-for="record in records" :key="record.id" class="workspace-card application-card"><div><strong>投递 #{{ record.id }}</strong><small>JD #{{ record.jobDescriptionId }} · 简历版本 #{{ record.resumeVersionId }}</small></div><select :value="record.status" aria-label="投递状态" @change="changeStatus(record, ($event.target as HTMLSelectElement).value as ApplicationStatus)"><option v-for="status in statuses" :key="status" :value="status">{{ status }}</option></select><label>反馈与跟进<textarea :value="feedbackDraft[record.id] ?? record.feedbackText ?? ''" rows="3" @input="feedbackDraft[record.id] = ($event.target as HTMLTextAreaElement).value" /></label><button class="btn-neon btn-secondary" type="button" @click="saveFeedback(record)">保存反馈</button><p v-if="record.coverLetterText">求职信：{{ record.coverLetterText }}</p><p v-if="record.openingMessageText">开场白：{{ record.openingMessageText }}</p></article></div>
    <p v-else class="empty-state">暂无投递记录。可从已确认的 JD 和简历版本创建记录。</p>
  </section>
</template>
