/**
 * 认证与安全面：注册登录、JWT、刷新会话、凭证变更，以及全站限流。
 *
 * <h2>读这一片的顺序</h2>
 * <ol>
 *   <li>{@code ratelimit.RateLimitRegistry} —— <b>唯一</b>表达「哪些请求受限、按什么分桶、
 *       阈值多少、为什么」；{@code RateLimitFilter} 只负责桶机制；</li>
 *   <li>登录 / 刷新 / 注册 —— access token 短 TTL（默认 3600s），refresh 走 HttpOnly Cookie；</li>
 *   <li>每请求用户状态校验 —— 用短 TTL 缓存（默认 30s）抵消每请求查库；删号提交后<b>即时清除</b>，
 *       而直接改库这类旁路最迟一个 TTL 后生效（这是刻意的取舍，不是遗漏）；</li>
 *   <li>凭证变更 —— 改邮箱/改密码都<b>必须校验当前密码</b>，成功后撤销该用户的刷新会话，
 *       要求所有设备重新登录。</li>
 * </ol>
 *
 * <h2>限流的三条设计约束</h2>
 * <ul>
 *   <li><b>分桶键是「规则组 + IP」，不是 URL</b>：按 URL 分桶会让阈值随 URL 基数线性放大
 *       （换个 {@code id} 即绕过）；</li>
 *   <li><b>结构上不存在无上限的写端点</b>：{@code write-default} 兜底所有未命中的写操作；</li>
 *   <li><b>429 必须带 {@code Retry-After} 与 {@code X-RateLimit-Rule}</b>：没有前者客户端只能
 *       盲目重试，没有后者线上无法反推命中了哪条规则。</li>
 * </ul>
 *
 * <p>已知边界：分桶维度只有 IP，没有用户 —— 同一 NAT 下多人共享预算，多出口攻击者可绕过；
 * 账号级成本上限目前由 AI 日配额承担。
 */
package com.intelligentresume.auth;
