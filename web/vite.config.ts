import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    strictPort: true,
  },
  build: {
    rollupOptions: {
      output: {
        // 稳定依赖拆独立 chunk，提升浏览器缓存命中率
        manualChunks(id: string) {
          if (!id.includes('node_modules')) return undefined
          // vue 全家桶（vue / @vue/* / pinia / vue-router）
          if (/[\\/](vue|@vue|pinia|vue-router)[\\/]/.test(id)) return 'vue-vendor'
          // 网络层
          if (/[\\/]axios[\\/]/.test(id)) return 'axios'
          // 图标库（体积较大且几乎不变，独立缓存）
          if (/[\\/]lucide-vue-next[\\/]/.test(id)) return 'lucide'
          // 其余第三方依赖
          return 'vendor'
        },
      },
    },
  },
})
