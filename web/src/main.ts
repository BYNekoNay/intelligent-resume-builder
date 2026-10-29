import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from './App.vue'
import router from './router'
import { ensureLocaleMessages, useLocale } from './i18n'
import './styles/main.css'

/**
 * 启动前置：把当前语言的文案**先加载完**再挂载。
 *
 * 为什么不用 top-level await：Vite 默认构建目标不支持 TLA，会直接把构建打挂。
 * 为什么必须先加载：en-US 目录已按语言拆分（2026-10-29），英文用户若先挂载再加载，
 * 首帧会是一屏中文然后闪成英文——比多等一个网络往返更难看。
 */
async function bootstrap() {
  const { locale } = useLocale()
  await ensureLocaleMessages(locale.value)
  createApp(App).use(createPinia()).use(router).mount('#app')
}

void bootstrap()
