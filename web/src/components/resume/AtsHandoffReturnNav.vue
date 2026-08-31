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
