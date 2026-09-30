# ADR-010: interview_ai_attempt 生命周期（双层唯一约束 + 短事务 + 陈旧丢弃）

## Status: Accepted (2026-09-30)

## Background

AI 面试与 `ai_task` 的异步模型不同：它是**服务端持状态的同步交互**——用户提交回答后同步等待 AI 评估并返回下一题（`InterviewController.answer`），AI 调用耗时可达数十秒。这带来专属的一致性风险：

1. **同一轮回答被重复评估**：网络超时重试或用户刷新重发，会让一轮回答产生两次评估、两条记录，轮次与报告失真。
2. **回答丢失**：若 AI 调用失败后回答只留在请求里，用户必须重新输入（长答案体验极差）。
3. **重试竞态**：旧 AI 调用的慢响应若在新一轮状态写回后到达，会覆盖新状态（脏写）。
4. **失败后的归宿**：AI 不可用时用户应能（a）重试，（b）或显式降级到规则模式继续，两者语义必须区分。

实测证据（`docs/reviews/2026-09-25-*` 及既有测试）：`interview_ai_attempt` 表两层唯一约束、`pendingAnswer` 先持久化、`PROCESSING_TAKEOVER_SECONDS=600` 接管、两阶段短事务 + 事务外 AI 的写回保护均已上线；本 ADR 将其固化为契约。

## Decision

1. **一次 AI 操作 = 一条 `interview_ai_attempt` 记录**（操作日志兼审计单元），状态机：
   `PROCESSING → SUCCESS | FAILED`；`FAILED →（用户重试）PROCESSING`；用户显式降级规则模式时 `FAILED → RULE_FALLBACK`（**终态**，不再回到 AI）。`operation_type ∈ {INITIAL_QUESTION, ANSWER_EVALUATION}`（`AiAttemptStatus`、`AiAttemptOperationType`）。
2. **双层唯一约束**（DDL `V20__ai_interview_flow.sql:42-43`，实体同步声明 `InterviewAiAttempt.java:11-14`）：
   - 请求层 `(user_id, idempotency_key)`：跨会话防重放；
   - 业务层 `(session_id, operation_type, round_no)`：**每轮每操作至多一条**，即使客户端换新 key 提交同一轮回答，服务端也会命中既有 attempt 而非产生第二条（`InterviewRuleService.java:117-128`）。
3. **重放校验指纹**：按 `(sessionId, roundNo, answer)` 生成安全指纹；同 key 重放但会话/操作类型/内容不符 → `40901`（`InterviewAnswerService.java:88-96`、`InterviewStartService.java:84-90`）。PROCESSING 且 `updatedAt` 超 600s 未更新 → 判定 `PROCESSING_TIMEOUT`，标记 `FAILED` 且可重试（`InterviewOperationSupport.java:108-111`、`InterviewAnswerService.java:97-101`）。
4. **回答先落盘**：attempt 创建时即将回答原文写入 `pending_answer`（`InterviewOperationSupport.java:196-217`）；评估失败后重试直接取 `pending_answer` 继续，**用户无需重新输入**（`InterviewRetryService.java:155-159`）。
5. **两阶段短事务 + 事务外 AI**：TX1 短事务做状态校验与占位（`attemptCount+1`，回到 `PROCESSING`）；AI 调用在事务外执行；TX2 短事务写回前用 `attemptCount` 作为 generation 校验 `isCurrentRetry`（session 状态 + executionMode + attempt 状态 + 计数四者匹配），不匹配则**丢弃结果**（`InterviewRetryService.java:62-117`、`:171-177`、`InterviewOperationSupport.java:113-119`）。这同时避免了长事务占用数据库连接。
6. **失败可重试性由服务端判定**：`markAttemptFailed` 记录 `retryable` 与错误码（`InterviewOperationSupport.java:183-194`）；retry 入口拒绝 `retryable=false` 的 attempt，唯一例外：错误码为 `FORBIDDEN` 且用户已重新授予面试同意 → 复原为可重试（`InterviewRetryService.java:75-82`）。重试前重校验配额，配额不足则 attempt 保持 `FAILED`（错误码更新为 `RATE_LIMITED`），不进入 AI 调用。
7. **规则降级单向且显式**：仅 `AI_ACTION_REQUIRED` 状态可 `continue-with-rules`；降级时把所有 `FAILED` attempt 标为 `RULE_FALLBACK`（`InterviewRuleService.java:56-75`）；规则模式下重复提交由 `ruleCompleted` 结果标记短路重放（`InterviewRuleService.java:100-104`）。

## Consequences

**正面**
- 客户端重试安全：同 key 重放与换 key 重提均不会产生重复评估（双层约束兜底）。
- AI 失败不吞用户输入：`pending_answer` 先落盘，重试与降级都不要求用户重打答案。
- 陈旧结果不可能覆盖新状态：generation 校验把"慢响应"降级为丢弃 + 日志（`Discarded stale interview AI retry result`）。
- 审计完整：attempt 记录 `provider_code`/`model_code`/`prompt_version`/`provider_request_id`/`result_json`，报告可溯源。

**负面**
- **attempt 表随会话轮次线性增长**，当前无清理/归档策略；会话数与轮次增长后需要归档设计。
- **600s 接管是启发式**：极慢的 AI 调用（接近链路总预算 600s）可能仍被用户重放判为超时；由于 TX2 有 generation 校验，误判不会造成脏写，但会把一次「其实还在跑」的调用置为失败（该情形由用户重试恢复）。
- **`attemptCount` 一职两用**：既是重试次数又是 generation 编号，改动重试计数逻辑时必须同步审视 stale 判定（`isCurrentRetry` 依赖精确相等）。
- **规则降级不可逆**：`RULE_FALLBACK` 是终态，会话降级后没有回到 AI 模式的入口（有意为之，避免状态在两种评估来源间反复横跳）。
- 同一轮回答的"换 key 重提"依赖业务层唯一约束拦截：错误信息为「当前轮次已有不同的回答操作」（`40901`），客户端需按业务错误处理而非网络重试。

## Related ADRs

- ADR-009（ai_task 幂等状态机）：异步任务与面试 attempt 是**两套独立幂等域**；面试不走 `ai_task`，避免同步交互与会话状态机引入双重状态源
- `docs/05` §9.4（AI 面试契约：start/answer/follow-up/retry/continue-with-rules/finish/report）
- `server/src/main/resources/db/migration/V20__ai_interview_flow.sql`（表结构与唯一约束的 DDL 依据）