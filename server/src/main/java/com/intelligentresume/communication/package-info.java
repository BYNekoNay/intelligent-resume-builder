/**
 * 岗位沟通文案：求职信、邮件、开场白。
 *
 * <h2>铁律：绝不自发送</h2>
 * 本包只<b>生成草稿</b>并持久化，任何情况下都不调用邮件/IM 发送接口。
 * 发送动作永远由人在本系统之外完成 —— 这是产品级的边界，不是尚未实现的功能。
 *
 * <h2>事实边界</h2>
 * 文案只能基于<b>已确认的简历版本与 JD 事实</b>（{@code CommunicationGenerationSource}）。
 * {@code CommunicationAiResultValidator} 会校验输出没有引入源材料之外的事实、数字、机构或链接；
 * 越界即判失败，而不是"清洗后放行"。
 *
 * <h2>模板</h2>
 * {@code communication_template} 有 14 行内置<b>只读种子</b>（V23 + V29），用户模板走乐观锁。
 * 占位符由 {@code TemplatePlaceholderService} 统一解析，预览与生成必须走同一套替换逻辑。
 *
 * <h2>异步与令牌</h2>
 * AI 生成与预览同样要求 {@code X-AI-Preview-Token}（见 {@code PROJECT_CONTEXT.md} R32），
 * 且与其它 AI 端点共享突发限流桶（{@code ai-generate}）。
 */
package com.intelligentresume.communication;
