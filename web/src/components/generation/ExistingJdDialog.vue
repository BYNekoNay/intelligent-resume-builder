<script setup lang="ts">
import { useLocale } from '@/i18n'
import { useDraftReview } from '@/composables/useDraftReview'

const { t } = useLocale()
const { showJdDialog, existingResumes, closeJdDialog } = useDraftReview()

const emit = defineEmits<{
  confirm: [targetResumeId: number | null]
}>()
</script>

<template>
  <Teleport to="body">
    <div v-if="showJdDialog" class="dialog-overlay" @click.self="closeJdDialog">
      <div class="dialog">
        <h3>{{ t('generationConfirm.existingResumeTitle') }}</h3>
        <p>{{ t('generationConfirm.existingResumeDesc') }}</p>
        <div class="existing-list">
          <div v-for="r in existingResumes" :key="r.id" class="existing-item">
            <span>{{ r.title }}</span>
            <button class="btn-small" @click="emit('confirm', r.id)">{{ t('generationConfirm.updateResume') }}</button>
          </div>
        </div>
        <div class="dialog-actions">
          <button class="btn-primary" @click="emit('confirm', null)">{{ t('generationConfirm.createNewResume') }}</button>
          <button class="btn-secondary" @click="closeJdDialog">{{ t('generationConfirm.cancel') }}</button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.btn-primary {
  padding: 0.6rem 1.5rem;
  background: #0e7490;
  color: #fff;
  border: none;
  border-radius: 6px;
  font-size: 0.9rem;
  cursor: pointer;
  font-weight: 500;
  display: flex;
  align-items: center;
  gap: 0.4rem;
}
.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.btn-secondary {
  padding: 0.6rem 1.5rem;
  background: #fff;
  color: #374151;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font-size: 0.9rem;
  cursor: pointer;
}
.btn-small {
  font-size: 0.75rem;
  padding: 0.2rem 0.5rem;
  background: #0e7490;
  color: #fff;
  border: none;
  border-radius: 4px;
  cursor: pointer;
}
.dialog-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}
.dialog {
  background: #fff;
  border-radius: 12px;
  padding: 1.5rem;
  max-width: 480px;
  width: 90%;
  max-height: 80vh;
  overflow-y: auto;
}
.dialog h3 {
  margin-bottom: 0.75rem;
}
.existing-list {
  margin: 1rem 0;
}
.existing-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.5rem 0;
  border-bottom: 1px solid #f3f4f6;
}
.dialog-actions {
  display: flex;
  gap: 0.75rem;
  margin-top: 1rem;
}
.btn-primary, .btn-secondary, .btn-small { display: inline-flex; align-items: center; justify-content: center; min-height: 36px; padding: 0 13px; border: 1px solid var(--border); border-radius: 6px; font-size: 11px; font-weight: 650; cursor: pointer; }
.btn-primary, .btn-small { border-color: var(--accent); color: #fff; background: var(--accent); }
.btn-secondary { color: var(--text-secondary); background: var(--bg-surface); }
.btn-secondary:hover { border-color: var(--accent); color: var(--accent); background: var(--accent-light); }
.dialog-overlay { padding: 20px; background: rgba(18, 36, 27, .48); backdrop-filter: blur(5px); }
.dialog { max-width: 500px; padding: 24px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg-surface); box-shadow: var(--shadow-lg); }
.dialog h3 { margin: 0 0 8px; color: var(--text-primary); font-family: var(--font-display); font-size: 22px; }
.dialog p { color: var(--text-secondary); font-size: 11px; }
.existing-item { border-bottom-color: var(--border-soft); color: var(--text-primary); font-size: 12px; }
.dialog-actions { justify-content: flex-end; gap: 8px; }

@media (max-width: 560px) {
  .dialog-overlay { align-items: end; padding: 0; }
  .dialog { width: 100%; max-width: none; border-bottom: 0; border-radius: 8px 8px 0 0; }
}
</style>
