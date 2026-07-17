<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useResumeStore } from '@/stores/resume'
import { createResume, deleteResume } from '@/api/resume'

const store = useResumeStore()
const title = ref('')
const name = ref('')
const saving = ref(false)
const error = ref('')

onMounted(() => store.load())

async function create() {
  saving.value = true
  error.value = ''
  try {
    await createResume(title.value, { basics: { name: name.value }, work: [], education: [], skills: [], projects: [] })
    title.value = ''
    name.value = ''
    await store.load()
  } catch {
    error.value = '简历创建失败，请检查名称后重试。'
  } finally {
    saving.value = false
  }
}

async function remove(id: number) {
  if (!window.confirm('删除后将不再出现在列表中，确定继续吗？')) return
  await deleteResume(id)
  await store.load()
}
</script>

<template>
  <section class="workspace-page">
    <h1>简历列表</h1>
    <form class="workspace-card inline-form" @submit.prevent="create">
      <label>简历名称<input v-model.trim="title" required maxlength="255" placeholder="例如：Java 后端工程师" /></label>
      <label>姓名<input v-model.trim="name" required placeholder="用于创建 basics 骨架" /></label>
      <button class="btn-neon btn-primary" :disabled="saving">{{ saving ? '正在创建…' : '新建简历' }}</button>
    </form>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <p v-if="store.loading">加载中...</p>
    <p v-else-if="!store.items.length" class="empty-state">还没有简历。创建第一份简历后即可用于岗位定制。</p>
    <div v-else class="job-list">
      <article v-for="r in store.items" :key="r.id" class="workspace-card job-card"><RouterLink :to="{ name: 'resume-detail', params: { id: r.id } }"><h2>{{ r.title }}</h2><p>最后更新：{{ r.updatedAt }}</p></RouterLink><button class="danger-action" title="删除简历" @click="remove(r.id)">删除</button></article>
    </div>
  </section>
</template>
