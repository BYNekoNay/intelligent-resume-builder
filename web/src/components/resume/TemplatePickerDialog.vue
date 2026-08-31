<script setup lang="ts">
import { useLocale } from '@/i18n'

const { t } = useLocale()

export interface TemplateOption {
  code: string
  name: () => string
  description: () => string
}

defineProps<{
  templateCode: string
  templateOptions: readonly TemplateOption[]
}>()

const emit = defineEmits<{
  select: [code: string]
  close: []
}>()
</script>

<template>
  <div class="template-picker-overlay" role="dialog" :aria-label="t('resumeEditor.templateChooserTitle')" @click.self="emit('close')">
    <section class="template-picker-dialog">
      <header><div><p class="eyebrow">{{ t('resumeEditor.templateLabel') }}</p><h2>{{ t('resumeEditor.templateChooserTitle') }}</h2><p>{{ t('resumeEditor.templateChooserDescription') }}</p></div><button class="btn-neon btn-ghost" type="button" @click="emit('close')">{{ t('common.close') }}</button></header>
      <div class="template-options template-options-dialog">
        <button v-for="opt in templateOptions" :key="opt.code" type="button" :class="{ active: templateCode === opt.code }" :aria-pressed="templateCode === opt.code" @click="emit('select', opt.code)"><span class="template-swatch" :class="`swatch-${opt.code}`" /><strong>{{ opt.name() }}</strong><small>{{ opt.description() }}</small><span v-if="templateCode === opt.code" class="template-selected">{{ t('resumeEditor.templateSelected') }}</span></button>
      </div>
    </section>
  </div>
</template>
