/**
 * 简历导入：从 TXT / PDF / DOCX 抽取文本并规范化成基础字段。
 *
 * <h2>两条安全约束</h2>
 * <ul>
 *   <li><b>原文件不落盘</b>：解析在内存中完成，导入完成后不保留原始文件 ——
 *       简历里是真实个人信息，留下副本等于扩大泄露面；</li>
 *   <li><b>解析结果只是建议</b>：抽出来的字段要经用户确认才写入资料库/简历，
 *       不做"上传即覆盖"。解析错字段比解析失败更难发现。</li>
 * </ul>
 *
 * <h2>它是 CPU 放大器，所以限流最严</h2>
 * PDFBox / POI 全内存解析最大 5MB 文件，是本站最重的 CPU 端点。
 * {@code RateLimitRegistry} 给它 {@code resume-import-parse} 规则（默认 6/min），
 * 不要因为"用户体验"把它放宽到与普通写操作同级。
 *
 * <p>注意区分：本包处理的是<b>用户上传的简历文件</b>；
 * 「Web 导出包 → 桌面工作区」的迁移走 {@code com.intelligentresume.desktop}。
 */
package com.intelligentresume.imports;
