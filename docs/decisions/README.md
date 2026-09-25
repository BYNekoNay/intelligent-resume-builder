# 决策登记册（Decision Register）

本目录记录**为什么这么做**，与 `docs/plans/`（方案）、`docs/reviews/`（评审）互补：
plans/reviews 面向"某次工作怎么做、结果如何"，本目录面向**跨时间可追溯的决策本身**。

## 两类产物

| 产物 | 用途 | 生命周期 |
| --- | --- | --- |
| `ADR-XXX-*.md` | 架构决策记录（MADR 精简格式） | **不可变**：已接受的 ADR 只做状态变更（Accepted → Superseded/Deprecated），正文不改写，纠正靠追加新 ADR |
| `OPEN-DECISIONS.md` | 悬而未决登记册 | **只追加 + 就地关闭**：OPEN → RESOLVED，并补 Resolution 字段 |

## ADR 格式

```markdown
# ADR-XXX: {决策标题}
## Status: Accepted (YYYY-MM-DD) | Superseded by ADR-YYY | Deprecated
## Background: 为什么需要做这个决策（包含被推翻的错误判断）
## Decision: 决定了什么
## Consequences: 正面/负面后果（必须写负面）
## Related ADRs: 关联决策
```

## OPEN-DECISIONS 三类固定 slug

- `waiting-on-external-condition`：等外部条件（用户确认 / 第三方审批 / 容量评估）
- `design-decision-to-evaluate`：设计待评估（需做 POC / 诊断对比）
- `existing-design-boundary`：现有设计边界约束

## 索引

| ADR | 标题 | 状态 |
| --- | --- | --- |
| [ADR-001](./ADR-001-bailian-quota-is-per-model.md) | 百炼免费额度按**模型**维度独立计量 | Accepted |
| [ADR-002](./ADR-002-model-chain-fallback-with-total-budget.md) | 模型链采用严格降级 + 总时间预算 | Accepted |
| [ADR-003](./ADR-003-provenance-marker-exclusivity-scope.md) | 溯源标记互斥只适用于要求溯源的节点 | Accepted |
| [ADR-004](./ADR-004-ai-poll-window-aligned-with-chain-budget.md) | 前端 AI 轮询窗口对齐链路总预算 | Accepted |
| [ADR-005](./ADR-005-disable-model-reasoning-for-generation.md) | 对生成类任务关闭模型推理（477s → 7s） | Accepted |
| [ADR-006](./ADR-006-keep-reasoning-for-ats-analysis.md) | 保留 ATS_ANALYSIS 推理（本轮实验未能回答该问题） | Accepted |
| [ADR-007](./ADR-007-shared-host-independent-port-and-database.md) | 测试环境迁入与另一项目共用的主机（独立端口 8088 + 独立 MySQL） | Accepted |
| [ADR-008](./ADR-008-configuration-binding-via-configuration-properties.md) | 配置绑定一律用 `@ConfigurationProperties`，禁止 `@Value` + SpEL map 字面量 | Accepted |
