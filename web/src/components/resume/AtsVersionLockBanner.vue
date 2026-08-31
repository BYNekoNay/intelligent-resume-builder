<script setup lang="ts">
import { FilePlus2, LockKeyhole } from 'lucide-vue-next'
import { useLocale } from '@/i18n'

const { t } = useLocale()

defineProps<{
  creating: boolean
  error: string
}>()

const emit = defineEmits<{
  create: []
}>()
</script>

<template>
  <section class="ats-version-lock" role="status">
    <LockKeyhole :size="18" />
    <div><strong>{{ t('resumeEditor.atsHistoricalTitle') }}</strong><p>{{ t('resumeEditor.atsHistoricalDescription') }}</p><p v-if="error" class="form-error" role="alert">{{ error }}</p></div>
    <button class="btn-neon btn-primary" type="button" :disabled="creating" @click="emit('create')"><FilePlus2 :size="15" />{{ creating ? t('resumeEditor.atsCreatingEditable') : error ? t('common.retry') : t('resumeEditor.atsCreateEditable') }}</button>
  </section>
</template>
