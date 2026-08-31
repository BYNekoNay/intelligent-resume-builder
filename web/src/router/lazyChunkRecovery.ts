const lazyChunkRetryPrefix = 'intelligent-resume.lazy-chunk-retry:'

export function lazyChunkRetryKey(fullPath: string) {
  return `${lazyChunkRetryPrefix}${fullPath}`
}
