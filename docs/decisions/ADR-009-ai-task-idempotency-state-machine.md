# ADR-009: ai_task 幂等状态机（幂等键 + 内容指纹 + 租约）

## Status: Accepted (2026-09-30)

## Background

AI 任务是异步作业：创建接口只返回 `taskId`，worker 在后台执行（`AiTask.java:53-55`、`DatabaseTaskWorker`）。这类接口天然面对三类重复风险：

1. **客户端重试**：网络超时后浏览器/移动端重发，或用户重复点击。若不做幂等，同一动作会创建两个任务，重复调用 AI、重复消耗配额。
2. **同键不同内容**：如果只用幂等键查重、不校验请求内容，用户修改输入后重发（复用同一键）会被静默返回**旧任务**，前端拿到与当前输入不符的结果。
3. **多实例 / 进程重启**：状态与执行进度若放内存，重启后任务卡死（PENDING 无人领取）或重复执行（RUNNING 双跑）。

同时系统还有独立的**结果确认流**（`SUCCESS + PENDING` → confirm/reject → 创建 `resume_version`，`docs/05` §7.6），它与任务执行状态机是两个生命周期，必须明确边界。

本 ADR 记录当前已上线实现的契约（各幂等端点 IT 已覆盖，其中 follow-up 端点重放 IT 见提交 `89d07bb`）。

## Decision

1. **幂等域 = `(user_id, task_type, idempotency_key)`**，数据库唯一键 `uk_ai_task_idem`（`V1__m1_m2_init.sql:190`），查询入口 `AiTaskRepository.findByUserIdAndTaskTypeAndIdempotencyKey`（`AiTaskRepository.java:31`）。同一 key 跨任务类型**不复用**。
2. **内容指纹**：对输入快照做规范化序列化（Map 键递归排序）后取 SHA-256 前 32 个十六进制字符（`IdempotencyService.java:31-41`、`:44-58`）。
3. **重放判定**（`AiTaskService.java:57-102`，顺序：同意校验 → 指纹 → 幂等检查 → 配额 → 创建）：
   - 同 key + 同指纹 → **直接返回已有任务**（不限状态；不重复执行、不再检查与消耗配额）；
   - 同 key + 不同指纹 → `40901 CONFLICT`（"相同幂等键的请求内容不一致"）。
4. **状态机**（`AiTaskStatus`）：
   - `PENDING` →（worker 领取租约）→ `RUNNING`：租约字段 `lease_owner` / `lease_expires_at`（`AiTask.java:74-78`）；
   - `RUNNING` 租约过期 → 可被重新领取（崩溃恢复）；重复领取的最终防线是条件更新 `acquireLease`（`AiTaskRepository.java:67-77`）；
   - `RUNNING` → `SUCCESS` | `FAILED`；终态含 `CANCELLED`；
   - `FAILED` →（仅用户 retry 接口）→ `PENDING`：`retryCount` 达上限拒绝，**`ATS_ANALYSIS` 豁免上限**（其失败多因模型超时等瞬时故障，实际消耗由日配额间接封顶，`AiTaskService.java:197-201`）；
   - `PENDING`/`RUNNING` → `CANCELLED`：仅账号注销级联（`AuthService.java:193`）。
5. **确认流独立于执行状态机**：仅 `SUCCESS + PENDING` 可确认；确认接口以 `taskUpdatedAt` 乐观锁防"结果已变化后误确认"，并在同一事务创建唯一 `resume_version`（`docs/05` §7.6）。
6. **任务类型白名单单点注册**：`AiTaskCapabilityRegistry` 是唯一注册点，枚举完整性 fail-closed（新增枚举未注册即抛 `IllegalStateException`，`AiTaskCapabilityRegistry.java:138-142`）；通用端点 `POST /api/ai/tasks` 仅接受 `genericEndpointAllowed=true` 的类型（`AiTaskController.java:42-46`），其余必须走域端点。

## Consequences

**正面**
- 客户端可无条件安全重发创建请求；重放零副作用（不重复执行、不重复消耗 AI 配额）。
- 「同键不同内容」显式返回 `40901`，把客户端 bug 暴露出来而不是静默给出错误结果。
- 多实例与进程重启安全：任务状态、租约、重试计数全部持久化在 `ai_task` 表。

**负面**
- **幂等键无过期窗口**：同一 `(user, taskType, key)` 永久指向同一任务；客户端必须保证 key 每次业务动作唯一（通常每次操作生成 UUID）。若客户端偷懒复用固定 key，新请求将永远拿到第一条任务。
- **指纹对数组顺序敏感**：规范化只排序 Map 键、保持 List 原序（`IdempotencyService.java:44-58`）。语义相同但数组元素顺序不同的重放请求会被判为"内容不一致"而返回 `40901`；重放必须与首次请求逐字段（含数组顺序）一致。
- **重放对 `FAILED` 任务同样返回失败结果**：失败恢复不能靠重发请求，必须显式调用 retry 接口（经过同意/配额重校验）。
- **`ATS_ANALYSIS` 重试豁免是特例分支**：新增任务类型时须显式评估是否沿用豁免，避免无意的无限重试。
- 幂等键长度受列约束（`VARCHAR(128)`，`AiTask.java:43-44`）；超长 key 在入库时失败而非被截断。

## Related ADRs

- ADR-002（模型链降级 + 总预算）：决定单个任务的耗时结构，与租约超时/重试语义耦合
- ADR-004（前端轮询窗口对齐链路总预算）：客户端据此解释 `PENDING`/`RUNNING`/`FAILED` 生命周期
- ADR-010（面试 attempt 生命周期）：面试提交走 `interview_ai_attempt` 而非 `ai_task`，两套幂等域并存但不混用
- `docs/05` §7.5–7.7（任务查询、确认/拒绝、异步创建契约）