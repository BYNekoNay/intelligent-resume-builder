<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ShieldCheck, ShieldOff } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import { getConsent, grantConsent, withdrawConsent, type ConsentResponse } from '@/api/ai'

const route = useRoute()
const router = useRouter()
const consent = ref<ConsentResponse | null>(null)
const loading = ref(true)
const message = ref('请阅读数据处理说明后，主动授权岗位定制功能使用你的资料与 JD。')
const granted = computed(() => consent.value?.eventType === 'GRANTED')
const redirectAfterConsent = computed(() => {
  const redirect = route.query.redirect
  return typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//') ? redirect : null
})

onMounted(async () => {
  try {
    consent.value = (await getConsent()).data.data
    if (granted.value) {
      message.value = '已恢复当前授权。你可以继续创建岗位定制任务，或随时在此撤回授权。'
    } else if (consent.value?.eventType === 'WITHDRAWN') {
      message.value = '当前授权已撤回。后续不会创建新的 AI 任务。'
    }
  } catch {
    message.value = '授权状态暂时无法获取，请检查网络后重试。'
  } finally {
    loading.value = false
  }
})

async function grant() {
  loading.value = true
  try {
    await grantConsent({
      policyVersion: 'mvp-v1',
      providerCode: 'configured',
      taskScopes: ['JOB_GENERATION', 'MATERIAL_RESUME_GENERATION', 'INLINE_OPTIMIZE', 'ACHIEVEMENT_GUIDANCE', 'COMMUNICATION_GENERATE', 'INTERVIEW'],
      dataCategories: ['CAREER_MATERIAL', 'JOB_DESCRIPTION', 'RESUME', 'RAW_MATERIAL_TEXT', 'INTERVIEW_ANSWER', 'TEXT_SELECTION'],
      noticeHash: 'mvp-local-notice-v1',
    })
    consent.value = (await getConsent()).data.data
    message.value = '已授权。你现在可以创建岗位定制任务，并可随时在此撤回授权。'
    if (redirectAfterConsent.value) {
      await router.replace(redirectAfterConsent.value)
    }
  } catch {
    message.value = '授权未完成，请检查登录状态和后端服务后重试。'
  } finally {
    loading.value = false
  }
}

async function withdraw() {
  loading.value = true
  try {
    await withdrawConsent()
    consent.value = (await getConsent()).data.data
    message.value = '已撤回授权。后续不会创建新的 AI 任务。'
  } catch {
    message.value = '撤回失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <section class="workspace-page narrow-page">
    <p class="eyebrow"><ShieldCheck :size="14" /> AI 数据处理</p>
    <h1>授权岗位定制</h1>
    <p class="page-lead">仅在你主动授权后，系统才会使用资料库、目标简历和 JD 生成待确认草稿。生成内容不会直接写入简历版本。</p>

    <article class="workspace-card consent-card">
      <h2>本次授权范围</h2>
      <ul>
        <li>职业资料、目标简历与当前 JD 仅用于岗位定制任务。</li>
        <li>每条生成要点都需要你逐项接受、编辑或拒绝。</li>
        <li>处理提供方以当前服务端配置为准，授权记录会显示实际使用的提供方。</li>
      </ul>
      <p class="status-line" :class="{ success: granted }" role="status">{{ message }}</p>
      <p v-if="consent" class="status-line">当前事件：{{ consent.eventType }} · 提供商：{{ consent.providerCode }} · 范围：{{ consent.taskScopes.join(', ') || '无' }}</p>
      <button v-if="!granted" class="btn-neon btn-primary" :disabled="loading" @click="grant">
        <ShieldCheck :size="16" /> {{ loading ? '正在授权…' : '同意并启用 AI' }}
      </button>
      <button v-else class="btn-neon btn-ghost" :disabled="loading" @click="withdraw">
        <ShieldOff :size="16" /> {{ loading ? '正在撤回…' : '撤回授权' }}
      </button>
    </article>
  </section>
</template>
