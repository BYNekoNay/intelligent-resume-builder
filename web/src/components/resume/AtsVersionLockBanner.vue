<!--
只读横幅：当前打开的是 ATS 体检时的历史版本，为保证诊断证据与原文对应，不允许直接编辑。
creating 为 true 时按钮禁用并显示「正在创建」；error 非空时按钮文案从「创建可编辑版本」改成「重试」。
唯一的 create 事件交给父组件真正去创建可编辑版本，本组件只负责呈现。
-->
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
