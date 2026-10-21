/**
 * 幂等键生成。
 *
 * <p>{@code crypto.randomUUID()} 只在**安全上下文**（HTTPS 或 localhost）可用。
 * 用 {@code http://<公网 IP>:端口} 访问部署时 {@code window.isSecureContext === false}，
 * 该函数是 {@code undefined}，直接调用会在请求发出**之前**抛错 —— 表现为
 * 「ATS 检查 / 沟通草稿 / 模拟面试」点击后只弹一句通用错误，接口从未被调用。
 *
 * <p>降级顺序：{@code randomUUID} → {@code getRandomValues}（同为 CSPRNG，广播域网关也支持）
 * → 时间戳 + 随机数兜底。三档都产出高熵字符串，满足幂等键「同一操作重复提交可去重」的用途。
 */
export function newIdempotencyKey(prefix?: string): string {
  const value = randomId()
  return prefix ? `${prefix}:${value}` : value
}

function randomId(): string {
  const webCrypto = globalThis.crypto

  if (typeof webCrypto?.randomUUID === 'function') {
    return webCrypto.randomUUID()
  }

  if (typeof webCrypto?.getRandomValues === 'function') {
    const bytes = webCrypto.getRandomValues(new Uint8Array(16))
    // 按 RFC 4122 v4 打版本与变体位，便于日志里与真实 UUID 区分检索
    bytes[6] = (bytes[6] & 0x0f) | 0x40
    bytes[8] = (bytes[8] & 0x3f) | 0x80
    const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
  }

  return `t${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}
