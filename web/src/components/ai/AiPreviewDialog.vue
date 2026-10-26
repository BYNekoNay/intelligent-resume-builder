<script setup lang="ts">
import { useLocale } from '@/i18n'
import type { AiTaskPreview } from '@/api/ai'

/**
 * 发送前预览弹窗（R32）—— 纯展示：状态由 `useAiTaskPreview` 持有。
 *
 * <p>刻意不在这里发请求：这样"预览 → 确认 → 才发任务"的顺序只能由组合式函数保证，
 * 不会被某个视图绕过去。
 */
defineProps<{
  visible: boolean
  loading: boolean
  errorMessage: string | null
  preview: AiTaskPreview | null
  redactions: Array<{ category: string; label: string; count: number }>
}>()

defineEmits<{
  (event: 'confirm'): void
  (event: 'cancel'): void
  (event: 'toggle-contact'): void
}>()

const { t } = useLocale()
</script>

<template>
  <Teleport to="body">
    <div v-if="visible" class="dialog-overlay" data-testid="ai-preview-overlay" @click.self="$emit('cancel')">
      <div class="dialog ai-preview" role="dialog" aria-modal="true" aria-labelledby="ai-preview-title">
        <header>
          <h3 id="ai-preview-title">{{ t('aiPreview.title') }}</h3>
        </header>

        <p class="ai-preview__hint">{{ t('aiPreview.description') }}</p>

        <div v-if="loading" class="ai-preview__loading">{{ t('aiPreview.loading') }}</div>
        <div v-else-if="errorMessage" class="ai-preview__error">{{ errorMessage }}</div>

        <template v-else-if="preview">
          <section class="ai-preview__meta">
            <span v-if="preview.includeContact" class="ai-preview__flag is-warning">
              {{ t('aiPreview.includesContact') }}
            </span>
            <span v-else class="ai-preview__flag">{{ t('aiPreview.excludesContact') }}</span>
            <ul v-if="redactions.length" class="ai-preview__redactions" data-testid="ai-preview-redactions">
              <li v-for="item in redactions" :key="item.category">{{ item.label }} × {{ item.count }}</li>
            </ul>
          </section>

          <ol class="ai-preview__messages" data-testid="ai-preview-messages">
            <li v-for="(message, index) in preview.messages" :key="index">
              <div class="ai-preview__role">{{ message.role }}</div>
              <pre class="ai-preview__content">{{ message.content }}</pre>
            </li>
          </ol>

          <label class="ai-preview__toggle">
            <input
              type="checkbox"
              data-testid="ai-preview-contact-toggle"
              :checked="preview.includeContact"
              @change="$emit('toggle-contact')"
            />
            {{ t('aiPreview.includeContact') }}
          </label>
        </template>

        <div class="dialog-actions">
          <button
            class="btn-primary"
            data-testid="ai-preview-confirm"
            :disabled="loading || !preview"
            @click="$emit('confirm')"
          >
            {{ t('aiPreview.confirm') }}
          </button>
          <button class="btn-secondary" data-testid="ai-preview-cancel" @click="$emit('cancel')">
            {{ t('aiPreview.cancel') }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.ai-preview {
  max-width: 46rem;
}
.ai-preview__hint {
  color: #64748b;
  font-size: 0.85rem;
  margin: 0 0 0.75rem;
}
.ai-preview__loading,
.ai-preview__error {
  padding: 1rem 0;
}
.ai-preview__error {
  color: #b91c1c;
}
.ai-preview__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  align-items: center;
  margin-bottom: 0.75rem;
}
.ai-preview__flag {
  font-size: 0.8rem;
  padding: 0.15rem 0.5rem;
  border-radius: 999px;
  background: #ecfdf5;
  color: #047857;
}
.ai-preview__flag.is-warning {
  background: #fef3c7;
  color: #b45309;
}
.ai-preview__redactions {
  display: flex;
  gap: 0.75rem;
  margin: 0;
  padding: 0;
  list-style: none;
  font-size: 0.8rem;
  color: #b45309;
}
.ai-preview__messages {
  margin: 0 0 0.75rem;
  padding: 0;
  list-style: none;
  max-height: 22rem;
  overflow: auto;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
}
.ai-preview__messages li + li {
  border-top: 1px solid #e2e8f0;
}
.ai-preview__role {
  font-size: 0.75rem;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: #64748b;
  padding: 0.4rem 0.75rem 0;
}
.ai-preview__content {
  margin: 0;
  padding: 0.4rem 0.75rem 0.75rem;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 0.82rem;
  line-height: 1.55;
}
.ai-preview__toggle {
  display: flex;
  gap: 0.5rem;
  align-items: center;
  font-size: 0.85rem;
}
</style>
