# ADR-005: 对生成类任务关闭模型推理

## Status: Accepted (2026-09-24)

## Background

`JOB_GENERATION` 单次耗时 477s（链首读超时 300s + 顺延 glm-5.3 约 177s），
远超用户耐心，并独占 worker 线程阻塞其他 AI 任务。

原假设是"prompt 规模过大导致链首超时"。**该假设被诊断推翻**（见
[plans/2026-09-23-003 §7](../plans/2026-09-23-003-known-issues-remediation.md)）：

- 重建的真实 prompt 仅 **6442 字符**；输入处理对照实验 **3.2s** 完成 → provider 未阻塞、输入不是瓶颈；
- 完整生成实测 `completion_tokens=8575`，其中 **`reasoning_tokens=7539`（88%）** —— 
  耗时在**不可见的推理**上，可见输出只占 12%；
- 连 `{"received": true}` 这种请求也会产生 42 个 reasoning token → 链上模型默认推理。

## Decision

对生成类任务的请求体增加 `enable_thinking: false`，按任务类型可配置
（`app.ai.bailian.disable-thinking-task-types`，默认 `JOB_GENERATION`）。

**必须成对的配套改动**：失败分类器对 400（非凭据类）由 `ABORT` 改为**顺延**。
逐模型验证发现 **3/8 个链成员不接受该参数**：

| 模型 | 表现 |
| --- | --- |
| `glm-5.3` | 拒绝 `enable_thinking`；`reasoning_effort` 只接受 low/high/max |
| `qwen3.8-2.4t-a95b` | 两者都拒绝 |
| `kimi-k3` | **拒绝 `temperature`** —— 本应用每次请求都带它 |

若不加分类器改动，这些模型的 400 会被判为 ABORT → **整条链立即终止**，后果比原问题更严重。

## Consequences

**正面**
- `JOB_GENERATION`：**477s → 7~12s**（线上实测 7s），链首直接成功、不再超时顺延。
- 输出契约不受影响：11 个章节齐全，按 `JobGenerationSchemaValidator` 规则复现校验**通过**。
- 连带缓解 worker 队首阻塞 —— 不再有长任务独占线程。

**负面**
- **丧失推理能力**。对"从已确认材料做结构化改写 + 标注溯源"这类任务实测无影响，
  但对判断类任务（ATS 语义分析、面试评估）的影响**尚未测量**，故此次只对
  `JOB_GENERATION` 生效，其余任务维持原状 —— 这是一个刻意保守的范围选择。
- 依赖"400 顺延"来跳过不接受该参数的模型，比维护一张硬编码的模型能力表更健壮，
  但会让这些模型每次请求多消耗一次约 1s 的失败往返。
- `kimi-k3` 因此成为纯失败跳转节点（它在任何请求下都必然 400）。

## Related ADRs
- ADR-001（额度按模型计量，链式调度使其成员能力差异变得可见）
- ADR-002（模型链调度与失败分类；本决策依赖其对 400 的顺延处置）
