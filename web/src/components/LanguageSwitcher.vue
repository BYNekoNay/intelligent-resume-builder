<!--
中英切换按钮组：调用 useLocale 的 setLocale，落库并同步 html lang 与标题。
切到 en-US 需要先动态加载英文文案 chunk，所以等待期间置 switching 防连点，
否则用户会以为没反应而重复点击、触发多次加载。
setLocale 内部加载失败时保持当前语言不变，这里只负责复位 busy，不额外报错。
-->
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
