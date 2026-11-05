<!--
从 ATS 报告跳入编辑器后的返回条：固定「返回 ATS 报告」，并带上 resultId 回到同一份检查结果。
resultId 只用于拼路由，本组件不发请求、不持有状态。
-->
<script setup lang="ts">
import { ArrowLeft } from 'lucide-vue-next'
import { useLocale } from '@/i18n'

const { t } = useLocale()

defineProps<{
  resultId: number
}>()
</script>

<template>
  <nav class="ats-handoff-return" :aria-label="t('resumeEditor.atsReturnNavigation')">
    <RouterLink :to="`/ats?result=${resultId}`"><ArrowLeft :size="14" />{{ t('resumeEditor.atsReturnToReport') }}</RouterLink>
  </nav>
</template>
