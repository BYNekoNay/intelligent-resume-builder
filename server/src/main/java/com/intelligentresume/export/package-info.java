/**
 * PDF 导出：任务化渲染、私有下载、容量拒绝与重试。
 *
 * <h2>两条渲染路径</h2>
 * <ul>
 *   <li><b>在线</b>：{@code PdfServiceClient} 调 {@code pdf-service}（Node + Puppeteer，
 *       4 并发 / 16 队列，每次渲染占一个 Chromium 上下文）；</li>
 *   <li><b>桌面</b>：{@code JavaResumePdfRenderer} 用 PDFBox 在本机渲染，
 *       不依赖 Node 与 Chromium。</li>
 * </ul>
 *
 * <h2>「可重试 ≠ 失败」——本包最重要的一条语义</h2>
 * pdf-service 在容量不足时返回 <b>503 + {@code Retry-After}</b>，这**不是**一次失败：
 * 服务端把它翻译成 {@code PdfCapacityException}，任务<b>回到 PENDING</b> 并写入
 * {@code next_attempt_at}（V39），在到期前不可被领取；只有达到 {@code max-attempts}
 * 才判 {@code FAILED}。
 *
 * <p>反过来，非 503 的失败<b>不得</b>被降级成"可重试"——那会把真实的渲染错误
 * 藏进无限重试里，用户永远等不到结论。
 *
 * <h2>租约与过期</h2>
 * 领取靠 {@code ExportTaskLeaseService}（V16 的租约列），避免多 worker 重复渲染；
 * {@code ExportExpiryService} 负责清理过期产物。<b>下载必须鉴权</b>，产物目录不对外直出。
 *
 * <p>部署侧对应 {@code intelligent-resume-pdf.service}（{@code MemoryMax=512M}），
 * 探针第 7 节会核对监听范围。
 */
package com.intelligentresume.export;
