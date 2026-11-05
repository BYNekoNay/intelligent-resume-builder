/**
 * 简历与版本：结构化内容的校验、字段归一、版本生命周期（保存 / 归档 / 恢复 / 设为当前）。
 *
 * <h2>版本语义（最容易改错的地方）</h2>
 * <ul>
 *   <li><b>每次保存产生独立版本</b>，不覆盖历史；错误内容靠「恢复为新版本」而不是删除；</li>
 *   <li><b>当前版本不能归档</b>；<b>归档版本不能设为当前</b> —— 两条互斥约束由服务层强制；</li>
 *   <li><b>「恢复为新版本」是复制</b>成一条新的 {@code RESTORED} 版本并设为当前，原版本保持不变；</li>
 *   <li>本阶段只做归档与恢复，<b>不提供永久删除</b>（物理删除只在留存清扫里按引用情况发生）。</li>
 * </ul>
 *
 * <h2>内容与版式分离</h2>
 * 内容存在 {@code resume_version.contentJson}，版式由 {@code templateCode} 单独表达：
 * 换模板不动内容，改内容不打乱版式。字段名必须与编辑器共享的章节注册表一致（默认 14 个栏目），
 * 常见别名由 {@code JsonResumeFieldMapper} 归一，再经 {@code JsonResumeValidator} 校验。
 *
 * <h2>导出契约</h2>
 * {@code JsonResumeExporter} 对齐官方 JSON Resume schema 的根节点（只允许 14 个键）：
 * 平台自有内容进 {@code meta.platform}，无法映射的顶层键进 {@code unknownSections} ——
 * <b>不静默丢弃</b>。
 *
 * <p>编辑器侧（草稿隔离、三态渲染）见 {@code web/src/views/ResumeEditorView.vue} 与
 * {@code web/src/composables/useResumeEditorDraft}。
 */
package com.intelligentresume.resume;
