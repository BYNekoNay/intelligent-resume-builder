import { ref } from 'vue'
import { listDesktopBackups } from '@/api/desktop'

/**
 * 「这个页面/入口在当前环境是否可用」的探测（ADR-012 阶段一~四）。
 *
 * <p>桌面端点只在 `desktop` profile 下存在：在线部署会返回 **404**（已登录）或 **401**（未登录）。
 * 因此不能靠构建期开关，只能发一次真实请求探测，并把结果缓存在模块级单例里。
 *
 * <p>三条约束：
 * <ul>
 *   <li>**不阻塞首屏**：探测在布局挂载后异步发起，超时 5s，失败就当作不可用；</li>
 *   <li>**只探一次**：`probe()` 幂等，并发调用共享同一个 Promise，避免每个组件各发一遍；</li>
 *   <li>**失败即不可用**：任何异常（401/404/网络/超时）都按不可用处理，不区分原因 ——
 *       对用户来说结论都一样，而区分原因只会让入口在 401 时闪一下。</li>
 * </ul>
 */
const available = ref(false)
const probed = ref(false)
let pending: Promise<boolean> | null = null

export function useDesktopMode() {
  async function probe(force = false): Promise<boolean> {
    if (probed.value && !force) return available.value
    if (pending) return pending
    pending = listDesktopBackups()
      .then(() => {
        available.value = true
        return true
      })
      .catch(() => {
        available.value = false
        return false
      })
      .finally(() => {
        probed.value = true
        pending = null
      })
    return pending
  }

  return { available, probed, probe }
}
