/**
 * 懒加载分包失败的「一次性重试」标记：按路由 fullPath 生成 sessionStorage 键。
 * 用途是让 router.onError 在刷页重试前先落一个标记，避免同一路径无限刷新。
 */
const lazyChunkRetryPrefix = 'intelligent-resume.lazy-chunk-retry:'

export function lazyChunkRetryKey(fullPath: string) {
  return `${lazyChunkRetryPrefix}${fullPath}`
}
