<script setup lang="ts">
import { useLocale } from '@/i18n'

const { t } = useLocale()

const emit = defineEmits<{
  confirm: []
  close: []
}>()
</script>

<template>
  <div class="sample-confirm-overlay" @click.self="emit('close')">
    <div class="sample-confirm-card">
      <h3>{{ t('resumeEditor.loadSampleTitle') }}</h3>
      <p>{{ t('resumeEditor.loadSampleDesc') }}</p>
      <div class="sample-confirm-actions">
        <button class="btn-neon btn-ghost" type="button" @click="emit('close')">{{ t('common.cancel') }}</button>
        <button class="btn-neon btn-primary" type="button" @click="emit('confirm')">{{ t('resumeEditor.confirmLoadSample') }}</button>
      </div>
    </div>
  </div>
</template>
