<!--
本地草稿恢复提示条：进入编辑器时若命中同版本未保存草稿，询问「恢复」还是「丢弃」。
只抛 restore / discard，读写与清理 localStorage 全部由 useResumeEditorDraft 负责，这里不碰存储。
两个按钮互斥，点击后由父组件决定是否隐藏本提示。
-->
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
