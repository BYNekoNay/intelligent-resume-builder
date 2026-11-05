<!--
简历模板选择弹窗：templateOptions 由父组件提供，name / description 是函数而不是字符串，
这样模板名与说明能跟随当前语言，切换语言后无需重建选项数组。
select 事件带模板 code；选中态用 templateCode 比对，并同步 aria-pressed。
点击遮罩自身或关闭按钮都只抛 close，选模板与否由父组件决定。
-->
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
