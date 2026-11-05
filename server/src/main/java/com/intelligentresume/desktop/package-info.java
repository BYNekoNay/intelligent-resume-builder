/**
 * Windows 桌面本地工作区：导出包 / 导入包的预览与执行、备份恢复、本地 AI 设置。
 *
 * <h2>与 Web 端共用代码，但数据边界完全不同</h2>
 * 桌面 profile 用 <b>H2 文件库</b> + <b>免登录的恒定本地用户</b>，PDF 走纯 Java（PDFBox），
 * 不依赖 Node 与 Chromium。同一个 jar，靠 {@code --spring.profiles.active=desktop} 切换。
 *
 * <h2>导入链路（按调用顺序读）</h2>
 * <ol>
 *   <li>{@code ImportCollectionRegistry} —— 支持哪些集合、谁是父、谁是子，**唯一清单**；</li>
 *   <li>{@code DesktopImportPlanner} —— 预览分类：新增 / 冲突 / 跳过，嵌套集合与顶层同规则；</li>
 *   <li>{@code ImportContentComparator} —— 判断「本地这行」与「包里这条」是否同内容；</li>
 *   <li>{@code ImportValueMapper} + {@code ImportColumnBinder} —— 驼峰转列名、外键全部重映射；</li>
 *   <li>{@code DesktopImportWriter} —— 按依赖顺序落库，循环外键先置空后回填。</li>
 * </ol>
 *
 * <h2>三条硬约定</h2>
 * <ul>
 *   <li><b>非核心集合整份拒绝</b>（面试会话、答案资产、沟通模板、草稿、同意记录），不是静默丢弃；</li>
 *   <li><b>必填外键映射不到就整份拒绝</b>：写半份数据比失败更危险，用户会以为导入成功；</li>
 *   <li><b>密钥不经本包明文落地</b>：本地 AI 密钥走 DPAPI 加密，且<b>仅 Windows 可用</b>。</li>
 * </ul>
 *
 * <p>架构决策见 {@code docs/decisions/ADR-012-windows-desktop-local-workspace.md}；
 * 往返验证见 {@code docs/reviews/2026-10-23-export-import-roundtrip.md}。
 */
package com.intelligentresume.desktop;
