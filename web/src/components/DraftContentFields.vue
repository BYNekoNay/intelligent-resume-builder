<script setup lang="ts">
import { computed } from 'vue'
import { useLocale } from '@/i18n'

defineOptions({ name: 'DraftContentFields' })

const { t } = useLocale()

const props = withDefaults(defineProps<{
  modelValue: unknown
  editable?: boolean
}>(), {
  editable: false,
})

const emit = defineEmits<{
  'update:modelValue': [value: unknown]
}>()

const FIELD_LABEL_KEYS: Record<string, string> = {
  name: 'draftFields.name', title: 'draftFields.title', position: 'draftFields.position',
  role: 'draftFields.role', email: 'draftFields.email', phone: 'draftFields.phone',
  location: 'draftFields.location', website: 'draftFields.website', summary: 'draftFields.summary',
  company: 'draftFields.company', school: 'draftFields.school', degree: 'draftFields.degree',
  major: 'draftFields.major', issuer: 'draftFields.issuer', date: 'draftFields.date',
  startDate: 'draftFields.startDate', endDate: 'draftFields.endDate', period: 'draftFields.period',
  description: 'draftFields.description', highlights: 'draftFields.highlights',
  keywords: 'draftFields.keywords', level: 'draftFields.level',
  credentialId: 'draftFields.credentialId', url: 'draftFields.url',
}

const TEXTAREA_FIELDS = new Set(['summary', 'description'])

const isArray = computed(() => Array.isArray(props.modelValue))
const isObject = computed(() => isRecord(props.modelValue))
const objectEntries = computed(() => isRecord(props.modelValue) ? Object.entries(props.modelValue) : [])
const isEmpty = computed(() => {
  if (props.modelValue === null || props.modelValue === undefined || props.modelValue === '') return true
  if (Array.isArray(props.modelValue)) return props.modelValue.length === 0
  return isRecord(props.modelValue) && Object.keys(props.modelValue).length === 0
})

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function isNested(value: unknown) {
  return Array.isArray(value) || isRecord(value)
}

function fieldLabel(key: string) {
  const i18nKey = FIELD_LABEL_KEYS[key]
  if (i18nKey) return t(i18nKey)
  return key.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/_/g, ' ')
}

function displayValue(value: unknown) {
  if (typeof value === 'boolean') return value ? t('draftFields.yes') : t('draftFields.no')
  return value === null || value === undefined || value === '' ? t('draftFields.notFilled') : String(value)
}

function shouldUseTextarea(key: string, value: unknown) {
  return TEXTAREA_FIELDS.has(key) || String(value ?? '').length > 80
}

function coerceValue(original: unknown, value: string) {
  if (typeof original === 'number') return Number(value)
  if (typeof original === 'boolean') return value === 'true'
  return value
}

function updateObjectField(key: string, value: unknown) {
  if (!isRecord(props.modelValue)) return
  emit('update:modelValue', { ...props.modelValue, [key]: value })
}

function updateArrayItem(index: number, value: unknown) {
  if (!Array.isArray(props.modelValue)) return
  const next = [...props.modelValue]
  next[index] = value
  emit('update:modelValue', next)
}
</script>

<template>
  <p v-if="isEmpty" class="empty-value">{{ t('draftFields.emptyContent') }}</p>

  <div v-else-if="isObject" class="field-list">
    <div v-for="([key, value]) in objectEntries" :key="key" class="field-row">
      <div class="field-label">{{ fieldLabel(key) }}</div>
      <div class="field-value">
        <DraftContentFields
          v-if="isNested(value)"
          :model-value="value"
          :editable="editable"
          @update:model-value="updateObjectField(key, $event)"
        />
        <textarea
          v-else-if="editable && shouldUseTextarea(key, value)"
          :aria-label="fieldLabel(key)"
          :value="displayValue(value) === t('draftFields.notFilled') ? '' : displayValue(value)"
          rows="3"
          @input="updateObjectField(key, coerceValue(value, ($event.target as HTMLTextAreaElement).value))"
        />
        <select
          v-else-if="editable && typeof value === 'boolean'"
          :aria-label="fieldLabel(key)"
          :value="String(value)"
          @change="updateObjectField(key, ($event.target as HTMLSelectElement).value === 'true')"
        >
          <option value="true">{{ t('draftFields.yes') }}</option>
          <option value="false">{{ t('draftFields.no') }}</option>
        </select>
        <input
          v-else-if="editable"
          :aria-label="fieldLabel(key)"
          :value="displayValue(value) === t('draftFields.notFilled') ? '' : displayValue(value)"
          @input="updateObjectField(key, coerceValue(value, ($event.target as HTMLInputElement).value))"
        />
        <span v-else>{{ displayValue(value) }}</span>
      </div>
    </div>
  </div>

  <div v-else-if="isArray" class="value-list">
    <div v-for="(value, index) in (modelValue as unknown[])" :key="index" class="value-list-item">
      <DraftContentFields
        v-if="isNested(value)"
        :model-value="value"
        :editable="editable"
        @update:model-value="updateArrayItem(index, $event)"
      />
      <textarea
        v-else-if="editable"
        :aria-label="t('draftFields.itemIndex').replace('{index}', String(index + 1))"
        :value="displayValue(value) === t('draftFields.notFilled') ? '' : displayValue(value)"
        rows="2"
        @input="updateArrayItem(index, coerceValue(value, ($event.target as HTMLTextAreaElement).value))"
      />
      <span v-else>{{ displayValue(value) }}</span>
    </div>
  </div>

  <span v-else>{{ displayValue(modelValue) }}</span>
</template>

<style scoped>
.field-list {
  display: grid;
  gap: 10px;
}

.field-row {
  display: grid;
  grid-template-columns: minmax(88px, 118px) minmax(0, 1fr);
  gap: 14px;
  align-items: start;
}

.field-label {
  padding-top: 2px;
  color: #64748b;
  font-size: 12px;
  font-weight: 600;
}

.field-value {
  min-width: 0;
  color: #172033;
  font-size: 14px;
  line-height: 1.65;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.value-list {
  display: grid;
  gap: 7px;
}

.value-list-item {
  position: relative;
  padding-left: 15px;
}

.value-list-item::before {
  content: '';
  position: absolute;
  top: 10px;
  left: 1px;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #0e7490;
}

.value-list-item:has(.field-list) {
  padding: 0 0 12px;
  border-bottom: 1px solid #e8edf3;
}

.value-list-item:has(.field-list)::before {
  display: none;
}

.value-list-item:last-child {
  padding-bottom: 0;
  border-bottom: 0;
}

.empty-value {
  margin: 0;
  color: #94a3b8;
  font-size: 13px;
}

input,
textarea,
select {
  width: 100%;
  border: 1px solid #cbd5e1;
  border-radius: 5px;
  padding: 8px 10px;
  color: #172033;
  background: #fff;
  font: inherit;
  line-height: 1.5;
}

textarea {
  resize: vertical;
}

input:focus,
textarea:focus,
select:focus {
  outline: 2px solid rgba(14, 116, 144, 0.2);
  border-color: #0e7490;
}

@media (max-width: 560px) {
  .field-row {
    grid-template-columns: 1fr;
    gap: 3px;
  }
}
</style>
