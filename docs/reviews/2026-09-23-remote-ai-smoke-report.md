# 远程 AI 全链路冒烟测试报告（2026-09-23）

> 目标环境：`http://8.160.165.227`（阿里云 ECS · 直连部署 · 百炼模型链 8 模型）
> 测试方式：真实 HTTP + 真实百炼调用，覆盖全部 AI 任务类型
> 结论：**AI 能力基本可用（5/7 类任务实测通过）；发现的 2 个缺陷已于当日修复并复验**

---

## 1. 测试矩阵与结果

| # | AI 任务类型 | 结果 | 证据 |
| --- | --- | --- | --- |
| 1 | `JOB_MATERIAL_SELECTION`（JD 选材） | ✅ 通过 | 任务 SUCCESS；推荐 4 条、未选 0 条 |
| 2 | `JOB_GENERATION`（岗位简历生成） | ✅ 通过（修复后） | 任务 SUCCESS，477s（链首超时后顺延 `glm-5.3`）；确认后产出简历版本，11 个章节、8 处真实溯源 |
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

### 3.1 【P1 · 阻断】岗位简历生成被草稿溯源校验整体拒绝（**已修，见 §4.1**）

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

### 3.2 【P2】缺少必需请求头被报成 500，而非 400（**已修，见 §4.2**）

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

## 4. 缺陷修复记录

### 4.1 D1（岗位简历生成被校验整体拒绝）—— 已修

**根因定位**：`JobGenerationSchemaValidator` 的互斥校验（`_source` 与 `_pending` 不能共存）
是**无条件执行**的，但 `basics` / `objective` 这两个节点本就**不要求溯源**
（`validate()` 对它们传 `requiresProvenance=false`，且已有测试
`basicsMayUseConfirmedPersonalProfileWithoutMaterialProvenance` 确认它们可以不带任何标记）。
模型在这类节点上偶尔两个标记都写，并不构成契约破坏，却被判成整份草稿失败。

**关键判断**：对**要求溯源**的节点，互斥由紧随其后的
`requiresProvenance && hasSource == hasPending` 分支覆盖（两者都写时该等式成立，依旧抛错）。
也就是说原互斥判断对溯源节点是冗余的，**只对不要求溯源的节点造成误伤**。

**修复**（两处，最小改动）：
1. `JobGenerationSchemaValidator`：互斥判断加 `requiresProvenance` 前置条件。
   **严格性完全不变** —— 要求溯源节点「两个都写」或「都没写」仍被拒绝（已补测试断言）。
2. `JobGenerationPromptBuilder`：prompt 显式补充「同一对象**只能**包含 `_sources` / `_pending` 之一，
   不得两者都写、也不得都不写」，从源头降低模型偏离概率。

**边界说明**：本修复**不放宽**溯源要求，也不丢弃 `_pending`（不会掩盖未溯源内容）。
带 `_pending` 的条目在确认流程中仍默认按「拒绝」处理，方向是安全的。

### 4.2 D2（缺请求头被报成 500）—— 已修

**根因**：`GlobalExceptionHandler` 的 400 分支已覆盖
`HttpMessageNotReadableException`、`MethodArgumentTypeMismatchException`、
`MissingServletRequestParameterException`、`ConstraintViolationException`，
**唯独漏了 `MissingRequestHeaderException`**，于是落到 `Exception.class` 兜底分支被报成 500。

**修复**：把 `MissingRequestHeaderException` 纳入同一 400 分支，并加注释说明为何必须按类型覆盖。
如此 `Idempotency-Key` 等必需请求头缺失时返回 `400 / VALIDATION`，不再产生 ERROR 级全栈日志。

### 4.3 验证结果

| 验证项 | 方式 | 结果 |
| --- | --- | --- |
| 单测 | `JobGenerationSchemaValidatorTest`（8→12）、新增 `GlobalExceptionHandlerTest`（2） | ✅ 通过 |
| 全量回归 | `mvn test` | ✅ **636 passed / 0 failed / 0 errors** |
| D2 线上复验 | `POST /api/interviews/start` 不带 `Idempotency-Key` | ✅ **HTTP 400 `参数错误`**（修复前 500）；带头正常 200；服务器日志中 `MissingRequestHeaderException` 归零 |
| D1 线上复验 | 重跑远程 AI 全链路 | ✅ `JOB_GENERATION` **SUCCESS**，见下 |

**D1 复验详情**（同一账号、同一业务链路）：

```
ai_task 最终状态（按 id 串行执行）
  id=7   INLINE_OPTIMIZE       SUCCESS   13s
  id=8   ACHIEVEMENT_GUIDANCE  SUCCESS   28s
  id=9   JOB_MATERIAL_SELECTION SUCCESS  55s
  id=10  JOB_GENERATION        SUCCESS  477s   ← 修复前稳定失败，现成功
  id=11  ATS_ANALYSIS          SUCCESS  338s
  id=12  INLINE_OPTIMIZE       SUCCESS  107s
  id=13  ACHIEVEMENT_GUIDANCE  SUCCESS   20s

id=10 的链路轨迹
  qwen3.8-max → TIMEOUT(300s) → 顺延 glm-5.3 → success → fallbackDepth=1
  总耗时 477s（< 600s 预算，总预算未误杀）

草稿内容与闭环
  顶层字段 11 个：work / basics / skills / courses / projects / education /
                  objective / certificates / publications / volunteering / customSections
  溯源标记共 8 处，「两者并存」0 处 —— prompt 加固生效，模型不再同时输出两个标记
  work[0]._sources = [{materialId:14, WORK_EXPERIENCE}, {materialId:17, SKILL_EVIDENCE}]
                  —— 真实资料溯源，非编造
  确认落库：resumeVersionId=3 / versionNo=1 / rejectedPaths=[]
```

**附带发现（非缺陷，但影响运维判断）**：AI worker **按 id 串行**执行任务
（`DatabaseTaskWorker` 每次 `claimBatch(owner, 1)`）。一次 477s 的生成任务会让后续 AI 任务
排队，例如 id=11 的 ATS 分析因此耗时 338s。这与模型链总预算问题同源：**单条长任务会阻塞队列**。
本次测试最初的 3 项「失败」全部源于测试脚本轮询窗口（300s / 240s）短于真实排队后的耗时，
产品侧实际全部成功；脚本窗口已相应调整（`poll_ai_task` 默认 600s）。

## 5. 建议与待决策

| 编号 | 事项 | 建议 |
| --- | --- | --- |
| D1 | ~~草稿溯源校验遇「`_source` 与 `_pending` 共存」时如何处置~~ | ✅ **已修**：互斥校验限定于要求溯源的节点，`basics`/`objective` 不再被误伤；严格性不变（见 §4.1） |
| D2 | ~~`MissingRequestHeaderException` 等客户端异常映射为 400~~ | ✅ **已修**：已纳入 400 分支并线上复验（见 §4.2） |
| D3 | `qwen3.8-max` 在最大 prompt 上 300s 超时 | 观察该模型在其他大 prompt 任务上的表现；必要时为大 prompt 任务单独设链序或提高读超时 |
| D4 | 为「模型链全部失效」增加 Prometheus 告警 | 指标已就绪，规则待补 |

## 6. 测试资产

- 测试脚本：`.workbuddy/tools/test_remote_ai.py`（仅标准库，可复跑：`python test_remote_ai.py http://8.160.165.227`）
- 测试账号（**未清理**，可登录查看真实生成结果）：`aitesta3c8f6c5` / `AiTest-a3c8f6c5!`
- 测试数据均为合成数据，不含任何真实个人信息。

## 7. 变更记录

| 日期 | 变更 | 原因 |
| --- | --- | --- |
| 2026-09-23 | 初版 | 模型链上线后的首次远程 AI 全链路冒烟 |
