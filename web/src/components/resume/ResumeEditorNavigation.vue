<!--
简历编辑器左侧章节导航：顶部是完成度进度条与下一步建议，中间是「设计与高级 / 预览」两个入口，
下面是章节大纲列表。
props 全部由父组件计算好后传入：sections 的 complete 决定勾选态，activeSection 决定高亮，
completionScore 直接作为进度条宽度，本组件不做任何统计。
select / openDesign / openPreview 三个事件上抛，导航自身不持有状态。
-->
<script setup lang="ts">
export interface ResumeEditorSection {
  key: string
  label: string
  meta: string
  complete: boolean
}

defineProps<{
  sections: ResumeEditorSection[]
  activeSection: string
  completionScore: number
  nextSuggestion: string
  completionLabel: string
  designLabel: string
  previewLabel: string
  sectionsLabel: string
  contentLabel: string
}>()

const emit = defineEmits<{
  select: [section: string]
  openDesign: []
  openPreview: []
}>()
</script>

<template>
  <aside class="resume-editor-navigation" :aria-label="sectionsLabel">
    <div class="editor-progress" role="status">
      <div class="progress-heading"><span>{{ completionLabel }}</span><strong>{{ completionScore }}%</strong></div>
      <div class="progress-track"><span :style="{ width: `${completionScore}%` }" /></div>
      <small>{{ nextSuggestion }}</small>
    </div>

    <div class="editor-navigation-actions">
      <button type="button" class="btn-neon btn-ghost" @click="emit('openDesign')">{{ designLabel }}</button>
      <button type="button" class="btn-neon btn-ghost" @click="emit('openPreview')">{{ previewLabel }}</button>
    </div>

    <nav class="editor-outline" :aria-label="contentLabel">
      <button
        v-for="section in sections"
        :key="section.key"
        type="button"
        :class="{ active: activeSection === section.key, complete: section.complete }"
        :aria-current="activeSection === section.key ? 'step' : undefined"
        @click="emit('select', section.key)"
      >
        <span>{{ section.label }}</span>
        <small>{{ section.meta }}</small>
      </button>
    </nav>

  </aside>
</template>
