<script setup lang="ts">
import { ref } from 'vue'
import { Languages } from 'lucide-vue-next'
import { useLocale } from '@/i18n'

const { locale, setLocale, t } = useLocale()

/**
 * 切到英文要等一次文案模块加载（它已从主包拆出），所以按钮期间置 busy：
 * 否则用户会以为点击没反应而连点，触发多次加载判断。
 */
const switching = ref(false)

async function switchTo(next: 'zh-CN' | 'en-US') {
  if (switching.value || locale.value === next) return
  switching.value = true
  try {
    await setLocale(next)
  } finally {
    switching.value = false
  }
}
</script>

<template>
  <div class="language-switcher" :aria-label="t('language.label')" role="group" :aria-busy="switching">
    <Languages :size="15" aria-hidden="true" />
    <button type="button" :class="{ active: locale === 'zh-CN' }" :aria-pressed="locale === 'zh-CN'" @click="switchTo('zh-CN')">{{ t('language.chinese') }}</button>
    <button type="button" :class="{ active: locale === 'en-US' }" :aria-pressed="locale === 'en-US'" @click="switchTo('en-US')">{{ t('language.english') }}</button>
  </div>
</template>
