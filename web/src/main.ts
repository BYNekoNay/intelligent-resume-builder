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
 *
 * ⚠ 但「先加载」不等于「加载失败就不许挂载」：en-US chunk 是按 hash 命名的，
 * 用户浏览器缓存了旧 index.html 而线上已换版时，这个 chunk 会 404。
 * 早期实现是裸 await，于是 `bootstrap()` reject → `void bootstrap()` 吞掉 →
 * **页面永远不挂载，白屏**。所以失败必须降级到内联的 zh-CN 继续挂载：
 * 英文文案加载失败只是语言不对，白屏是整个应用不可用。
 */
async function bootstrap() {
  const { locale } = useLocale()
  try {
    await ensureLocaleMessages(locale.value)
  } catch (cause) {
    // 不阻断挂载：zh-CN 是内联的，一定可用；用户再点一次语言开关会重新尝试加载。
    console.error('[i18n] 启动文案加载失败，回退中文挂载', cause)
  }
  createApp(App).use(createPinia()).use(router).mount('#app')
}

void bootstrap()
