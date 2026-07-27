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
