# ADR-003: 溯源标记互斥只适用于要求溯源的节点

## Status: Accepted (2026-09-23)

## Background

岗位简历生成（核心功能）稳定失败于：

```
Draft schema validation failed: basics: _source 与 _pending 不能同时存在
```

`JobGenerationSchemaValidator` 要求每个节点**恰好包含** `_source`/`_sources`/`_pending` 之一。
模型（glm-5.3）在 `basics` 上两者都写了，于是**整份草稿被拒绝、全部内容丢弃**。

关键事实：`validate()` 对 `basics` 与 `objective` 传 `requiresProvenance=false` ——
这两个节点由已确认的个人档案派生，**本就不要求**溯源信息，且已有测试
`basicsMayUseConfirmedPersonalProfileWithoutMaterialProvenance` 确认它们可以不带任何标记。

进一步核对发现：那条互斥判断**是无条件执行的**，而对**要求溯源**的节点，
紧随其后的 `requiresProvenance && hasSource == hasPending` 分支已经覆盖了"两者都写"
（此时等式成立，依旧抛错）。即原互斥判断对溯源节点**冗余**，只对不要求溯源的节点造成误伤。

## Decision

把互斥判断限定在 `requiresProvenance == true` 的节点上；
`basics` / `objective` 容忍两个标记并存。

**不放宽**任何溯源要求：要求溯源的节点「两个都写」或「都没写」仍被拒绝。
**不丢弃** `_pending`（不掩盖未溯源内容）—— 带 `_pending` 的条目在确认流程中仍标为"未决策"。

同时加固 prompt，显式声明"同一对象只能含其中之一，不得都写也不得都不写"，从源头降低偏离概率。

## Consequences

**正面**
- 岗位简历生成恢复可用（实测 477s 后 SUCCESS，草稿 11 个章节、8 处真实溯源）。
- 严格性不变，且误伤面收窄到"零"。

**负面**
- 校验器语义从"全域互斥"变为"按节点是否要求溯源区分"，理解成本略升（已在代码注释中说明推导过程）。

## Related ADRs
- 无（独立的契约语义修正）
