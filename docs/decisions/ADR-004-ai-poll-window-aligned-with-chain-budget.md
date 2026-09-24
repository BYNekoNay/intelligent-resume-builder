# ADR-004: 前端 AI 轮询窗口对齐链路总预算，而非单次读超时

## Status: Accepted (2026-09-23)

## Background

`useTaskPolling` 的默认窗口是 `150 × 2s = 300s`，注释明确写着"对齐后端
`BAILIAN_READ_TIMEOUT_S=300` 的推理窗口"。

ADR-002 上线后，任务耗时结构从"单次调用"变为"**首次超时 + 顺延 N 次**"，
窗口的取值依据与实际耗时结构脱钩。实测按前端完全相同的参数轮询真实生成任务，
**窗口到期时任务仍为 `RUNNING`**（最终 477s 才成功）。

配套暴露两个问题：
1. 超时分支与失败分支共用同一张 `.status-card.error` 卡片，并渲染「重试生成」按钮 ——
   而对运行中的任务重试会被服务端拒绝（`AiTaskService` 有 `FAILED` 状态守卫），
   该错误信息还会覆盖掉原文案，用户因此**既看不到进度也无法重试**。
2. `GenerationConfirmView#loadTask` 的 `finally` 统一把 `loading` 置 false，
   但 RUNNING/PENDING 分支已交给轮询且 `startPolling` 不恢复加载态 →
   模板所有分支都不匹配 → **内容区整片空白**，直到任务完成。
   触发路径正是工作台「继续办理」与超时文案提示的"刷新页面查看结果"。

## Decision

1. 轮询窗口对齐**链路总预算**（`AI_CHAIN_TOTAL_BUDGET_S=600s`）并预留串行排队余量：
   `useTaskPolling` 默认 300 次 × 2s；`AtsCheckView` 同步到 400 次 × 1.5s。
2. 「窗口耗尽」与「任务失败」**语义分离**：窗口耗尽渲染独立的软状态卡片
   （说明仍在后台执行 + 提供「前往工作台继续」入口，复用既有 `resumableTasks`），
   **不提供**重试；仅真实 `FAILED`/`CANCELLED` 保留重试。
3. `GenerationConfirmView#loadTask` 改为逐分支显式结束加载态（对齐同仓库
   `MaterialSelectionConfirmView` 的既有正确范式），轮询分支保持 `loading=true`。

## Consequences

**正面**
- 用户不再在任务正常执行时收到"超时"，也不会被引导到必然 400 的操作。
- 运行中直接打开/刷新页面不再白屏。
- 窗口与后端预算的关系被 e2e 断言锁死（`windowMs >= 600_000`），防止未来单方面调参再次脱节。

**负面**
- 单任务最多轮询 300 次（前端请求量上升）；未引入退避，列入后续可选优化。
- 「窗口耗尽 → 软状态卡片」为纯模板条件分支，e2e 未做机器断言（Playwright 时钟无法
  可靠驱动 300 轮 async 轮询），该分支渲染由代码评审保证 —— 已在方案文档中如实标注为覆盖边界。

## Related ADRs
- ADR-002（本决策对其产生的耗时结构变化做出响应）
