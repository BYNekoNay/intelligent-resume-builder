/**
 * 跨模块基础能力：统一响应信封、错误码、异常处理、Trace ID、可观测性与持久化基类。
 *
 * <h2>统一信封（前端依赖它做解包）</h2>
 * <pre>{@code { "code": 0, "message": "success", "data": {...}, "traceId": "..." }}</pre>
 * {@code code = 0} 才是成功；业务失败也走 HTTP 200 的语义仅限既有端点，
 * <b>新增端点请使用对应 HTTP 状态码 + 信封内的业务码</b>，不要自造第三套格式。
 *
 * <h2>几个容易误用的点</h2>
 * <ul>
 *   <li>{@code ErrorCode} 是错误码的<b>唯一权威</b>；文档里的错误码表目前仍是"建议"表述
 *       （见 {@code docs/05-接口设计说明书.md} 文首声明）；</li>
 *   <li>{@code TraceIdFilter} 写入的 traceId 会进日志与响应，<b>不得</b>用它承载用户信息；</li>
 *   <li>{@code ClientIpResolver} 决定限流分桶与会话审计的 IP 语义：默认<b>不信任</b>
 *       {@code X-Forwarded-For}，只有可信反代之后才开启，且要求最外层代理覆写该头；</li>
 *   <li>{@code FailureCategoryClassifier} 把失败归类为配额/瞬时/永久 —— AI 与 PDF 的重试语义
 *       都建立在它之上，改分类等于改重试行为。</li>
 * </ul>
 *
 * <h2>持久化基类</h2>
 * {@code BaseEntity} 提供审计列；{@code SoftDeletableEntity} 提供软删标记。
 * <b>并非所有实体都该继承</b>：例如 {@code ai_task} 刻意不继承，因为 worker 用原生 SQL
 * 手动控制 {@code updated_at}（原因写在 {@code AiTask} 的类注释里）。
 */
package com.intelligentresume.common;
