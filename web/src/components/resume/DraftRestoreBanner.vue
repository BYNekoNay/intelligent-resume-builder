<script setup lang="ts">
import { useLocale } from '@/i18n'

const { t } = useLocale()

const emit = defineEmits<{
  restore: []
  discard: []
}>()
</script>

<template>
  <div class="draft-restore-banner" role="dialog" :aria-label="t('resumeEditor.restoreDraftTitle')">
    <div><strong>{{ t('resumeEditor.restoreDraftTitle') }}</strong><p>{{ t('resumeEditor.restoreDraftDescription') }}</p></div>
    <div><button class="btn-neon btn-ghost" type="button" @click="emit('discard')">{{ t('resumeEditor.discardDraft') }}</button><button class="btn-neon btn-primary" type="button" @click="emit('restore')">{{ t('resumeEditor.restoreDraft') }}</button></div>
  </div>
</template>
