<script setup lang="ts">
import { X } from 'lucide-vue-next'
import { useLocale } from '@/i18n'
import { useDraftReview } from '@/composables/useDraftReview'
import DraftContentFields from '@/components/DraftContentFields.vue'

const { t } = useLocale()
const { showEditDialog, editValue, saveEdit, closeEdit } = useDraftReview()
</script>

<template>
  <Teleport to="body">
    <div v-if="showEditDialog" class="dialog-overlay edit-overlay" @click.self="closeEdit" @keydown.esc="closeEdit">
      <div class="dialog edit-dialog" role="dialog" aria-modal="true" aria-labelledby="draft-edit-title">
        <header><h3 id="draft-edit-title">{{ t('generationConfirm.editContentTitle') }}</h3><button class="icon-button" :aria-label="t('common.close')" :title="t('common.close')" @click="closeEdit"><X :size="18" /></button></header>
        <div class="edit-dialog__body"><DraftContentFields v-model="editValue" editable /></div>
        <div class="dialog-actions">
          <button class="btn-primary" @click="saveEdit">{{ t('generationConfirm.save') }}</button>
          <button class="btn-secondary" @click="closeEdit">{{ t('generationConfirm.cancel') }}</button>
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
.edit-dialog {
  max-width: 640px;
}
.dialog-actions {
  display: flex;
  gap: 0.75rem;
  margin-top: 1rem;
}
.btn-primary, .btn-secondary { display: inline-flex; align-items: center; justify-content: center; min-height: 36px; padding: 0 13px; border: 1px solid var(--border); border-radius: 6px; font-size: 11px; font-weight: 650; cursor: pointer; }
.btn-primary { border-color: var(--accent); color: #fff; background: var(--accent); }
.btn-secondary { color: var(--text-secondary); background: var(--bg-surface); }
.btn-secondary:hover { border-color: var(--accent); color: var(--accent); background: var(--accent-light); }
.dialog-overlay { padding: 20px; background: rgba(18, 36, 27, .48); backdrop-filter: blur(5px); }
.dialog { max-width: 500px; padding: 24px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg-surface); box-shadow: var(--shadow-lg); }
.dialog h3 { margin: 0 0 8px; color: var(--text-primary); font-family: var(--font-display); font-size: 22px; }
.dialog p { color: var(--text-secondary); font-size: 11px; }
.dialog-actions { justify-content: flex-end; gap: 8px; }

@media (max-width: 560px) {
  .dialog-overlay { align-items: end; padding: 0; }
  .dialog { width: 100%; max-width: none; border-bottom: 0; border-radius: 8px 8px 0 0; }
}

.icon-button { display: inline-grid; width: 30px; height: 30px; flex: 0 0 30px; padding: 0; place-items: center; border: 1px solid var(--border); border-radius: 5px; color: var(--text-secondary); background: var(--bg-surface); cursor: pointer; }
.icon-button:hover,
.icon-button.active { border-color: var(--accent); color: var(--accent); background: var(--accent-light); }

.edit-overlay { align-items: stretch; justify-content: flex-end; padding: 0; }
.edit-overlay .edit-dialog { display: grid; grid-template-rows: auto minmax(0, 1fr) auto; width: min(440px, 100%); max-width: 440px; max-height: none; height: 100%; padding: 20px; overflow: hidden; border-block: 0; border-right: 0; border-radius: 8px 0 0 8px; }
.edit-dialog > header { display: flex; align-items: center; justify-content: space-between; padding-bottom: 12px; border-bottom: 1px solid var(--border); }
.edit-dialog > header h3 { margin: 0; font-size: 20px; }
.edit-dialog__body { min-height: 0; overflow-y: auto; padding: 16px 4px; }
.edit-dialog .dialog-actions { margin: 0; padding-top: 12px; border-top: 1px solid var(--border); }

@media (max-width: 767px) {
  .edit-overlay .edit-dialog { width: 100%; max-width: none; border-left: 0; border-radius: 0; }
}
</style>
