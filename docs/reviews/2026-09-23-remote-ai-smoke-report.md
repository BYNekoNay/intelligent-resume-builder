# 远程 AI 全链路冒烟测试报告（2026-09-23）

> 目标环境：`http://8.160.165.227`（阿里云 ECS · 直连部署 · 百炼模型链 8 模型）
> 测试方式：真实 HTTP + 真实百炼调用，覆盖全部 AI 任务类型
> 结论：**AI 能力基本可用（5/7 类任务实测通过），发现 2 个待修缺陷，其中 1 个阻断岗位简历生成**

---

## 1. 测试矩阵与结果

| # | AI 任务类型 | 结果 | 证据 |
| --- | --- | --- | --- |
| 1 | `JOB_MATERIAL_SELECTION`（JD 选材） | ✅ 通过 | 任务 SUCCESS；推荐 4 条、未选 0 条 |
| 2 | `JOB_GENERATION`（岗位简历生成） | ❌ **失败** | `Draft schema validation failed: basics: _source 与 _pending 不能同时存在` |
| 3 | `ATS_ANALYSIS`（ATS 深度分析） | ✅ 通过 | `analysisSource=HYBRID`、规则分 68.0、语义覆盖 7 项、优先级建议 4 项 |
| 4 | `COMMUNICATION_GENERATE`（沟通文案） | ✅ 通过 | 草稿生成成功（长度 61） |
| 5 | `INLINE_OPTIMIZE`（内联润色） | ✅ 通过 | 候选 3 条，`requiresManualConfirmation=true`（符合"AI 只产候选"的产品原则） |
| 6 | `ACHIEVEMENT_GUIDANCE`（量化成果引导） | ✅ 通过 | 任务 SUCCESS |
| 7 | `INTERVIEW_COACH`（模拟面试） | ✅ 通过 | HTTP 200、`executionMode=AI`、`status=AI_ACTION_REQUIRED`（首题异步生成，属预期契约） |
| 8 | AI 授权闸门 | ✅ 通过 | 撤回授权后发起 AI 任务返回 **403**；重新授权后恢复 |

AI 产出质量抽样（ATS 真实生成内容，非模板）：

> 简历与岗位核心要求整体匹配：具备 Java、Spring Boot、MySQL、Redis 技能，且有订单服务开发、
> 性能优化与缓存改造经历；但缺少可量化的高并发场景证据，跨团队协作与沟通能力未体现。

## 2. 模型链线上表现

### 2.1 正常工作路径：链首直接服务

`JOB_MATERIAL_SELECTION` 由链首 `qwen3.8-max` 直接完成，32s，无降级。

### 2.2 降级救回路径：真实超时触发顺延

`JOB_GENERATION` 的 prompt 最大，在链首上触发了真实超时：

```
20:12:38  AI provider call started:  model=qwen3.8-max
20:17:38  AI provider call completed: model=qwen3.8-max  outcome=failure  category=TIMEOUT   ← 整 300s
20:17:38  Model chain falling back: from=qwen3.8-max → to=glm-5.3
20:20:21  glm-5.3 产出草稿（163s），任务进入下游溯源校验
```

**模型链按设计工作**：超时被正确归类为 `TIMEOUT`（瞬时故障，短冷却），自动顺延到下一个模型。
若无模型链，该任务在 20:17:38 就已直接失败。

### 2.3 可观测性验证

| 指标 | 观测值 |
| --- | --- |
| `resume_ai_model_chain_fallbacks_total{from="qwen3.8-max",to="glm-5.3",task_type="JOB_GENERATION"}` | `1.0` |
| `resume_ai_model_chain_available` | `8.0` |
| `resume_ai_provider_calls_total{model="qwen3.8-max",outcome="success"}` | 选材路径计入 |

## 3. 发现的问题

### 3.1 【P1 · 阻断】岗位简历生成被草稿溯源校验整体拒绝

`JOB_GENERATION` 最终失败于下游校验：

```
Generation failed: Draft schema validation failed: basics: _source 与 _pending 不能同时存在
```

- **规则**：`JobGenerationSchemaValidator` 要求每个节点**恰好包含** `_source` / `_sources` / `_pending` 之一，二者不可共存（`JobGenerationSchemaValidator.java:82-92`）。
- **prompt 契约**：`JobGenerationPromptBuilder` 要求模型输出 `_sources` 数组 **或** `_pending` 字段（`:66`）。
- **实际行为**：`glm-5.3` 在 `basics` 上同时输出了两者 → 校验器抛 `VALIDATION` → **整个任务失败，全部草稿内容丢弃**。

**性质**：模型未严格遵循 prompt，属既有设计（严格溯源）与模型行为之间的冲突，与模型链无关。
**影响**：岗位简历生成是核心功能，此路径下**完全不可用**。
**待决策**：三种取向各有取舍，需产品拍板（见 §4）。

> 附带观察：链只对**传输/提供方失败**做顺延，对**输出质量问题**不做顺延。
> 「换一个模型可能产出合规草稿」是有价值的候选能力，但会让同一个坏 prompt 在多个模型上重复消耗额度，
> 属独立设计议题。

### 3.2 【P2】缺少必需请求头被报成 500，而非 400

```
POST /api/interviews/start   （不带 Idempotency-Key）
→ HTTP 500  {"code":50001,"message":"系统异常"}

服务端异常：
org.springframework.web.bind.MissingRequestHeaderException:
  Required request header 'Idempotency-Key' for method parameter type String is not present
    at GlobalExceptionHandler（兜底分支，level=ERROR，输出完整堆栈）
```

- **问题**：客户端错误（缺请求头）被映射为**服务端错误**。调用方无法区分「我传错了」和「服务端坏了」，同时 ERROR 级全栈日志会造成告警噪音。
- **期望**：映射为 **400**，并给出明确字段名（如 `缺少必需请求头 Idempotency-Key`）。
- **修复位置**：`GlobalExceptionHandler` 增加 `MissingRequestHeaderException`（以及 `MissingServletRequestParameterException`、
  `HttpMessageNotReadableException`、`MethodArgumentTypeMismatchException` 等同类客户端异常）的处理分支。

### 3.3 已修复（本次测试中发现并当天修掉）

| 缺陷 | 根因 | 修复 |
| --- | --- | --- |
| `resume_ai_model_chain_available` 读数为 `NaN` | Micrometer `Gauge` 对 state 对象只持**弱引用**，supplier 注册后被 GC | `AppObservability` 强引用住已注册 supplier |
| 模型链总耗时无上界（最坏 8×300s ≈ 40 分钟，长时间占住 worker 线程） | 链长度会放大单次读超时 | 新增 `AI_CHAIN_TOTAL_BUDGET_S`（默认 600s），超预算停止顺延并快速失败 |

核实澄清：一度担心「读超时 300s > 租约 180s」会被另一 worker 抢占造成重复计费，经核实**不成立** ——
`TaskExecutionService.startHeartbeat()` 每 60s 在独立调度线程续租，worker 线程阻塞时仍能续租。

## 4. 建议与待决策

| 编号 | 事项 | 建议 |
| --- | --- | --- |
| D1 | 草稿溯源校验遇「`_source` 与 `_pending` 共存」时如何处置 | 三选一：**(a)** 校验器归一化（有 `_sources` 时丢弃 `_pending`，风险：可能放过未溯源内容）；**(b)** 校验失败时自动换链上下一个模型重生成（风险：重复消耗额度）；**(c)** 维持拒绝，但把失败降级为「草稿可用 + 标注待核实」而非整体丢弃。**需产品拍板** |
| D2 | `MissingRequestHeaderException` 等客户端异常映射为 400 | 直接修，属明确缺陷 |
| D3 | `qwen3.8-max` 在最大 prompt 上 300s 超时 | 观察该模型在其他大 prompt 任务上的表现；必要时为大 prompt 任务单独设链序或提高读超时 |
| D4 | 为「模型链全部失效」增加 Prometheus 告警 | 指标已就绪，规则待补 |

## 5. 测试资产

- 测试脚本：`.workbuddy/tools/test_remote_ai.py`（仅标准库，可复跑：`python test_remote_ai.py http://8.160.165.227`）
- 测试账号（**未清理**，可登录查看真实生成结果）：`aitesta3c8f6c5` / `AiTest-a3c8f6c5!`
- 测试数据均为合成数据，不含任何真实个人信息。

## 6. 变更记录

| 日期 | 变更 | 原因 |
| --- | --- | --- |
| 2026-09-23 | 初版 | 模型链上线后的首次远程 AI 全链路冒烟 |
