# 百炼模型链（有序降级 + 失效冷却）设计方案

> 生成日期：2026-09-23
> 状态：**待确认（未动工）**
> 依据：服务器实测 8 个模型可用性 + 现有 `BailianAiProvider` / `AiProviderRegistry` / `FailureCategoryClassifier` 源码

---

## 1. 问题定义（已实测确认）

### 1.1 根因：单模型硬编码

`application.yml` 只有一个 `app.ai.bailian.model`（env `BAILIAN_MODEL`），`BailianAiProvider` 把它写死进请求体。
一旦**这个模型**的免费额度耗尽，全部 AI 功能一起失败，且无任何自动恢复路径。

**上一轮部署时我判错了**：当时把 `qwen3.7-plus-2026-05-26` 的 403 判成「账号级额度耗尽」。本次实测纠正：

| 模型 | 实测结果 |
| --- | --- |
| `qwen3.7-plus-2026-05-26`（当时配置的） | HTTP 403 `AllocationQuota.FreeTierOnly` |
| `qwen-plus` | HTTP 403 `AllocationQuota.FreeTierOnly` |
| `qwen3.8-max` | **HTTP 200 正常** |

结论：**额度耗尽是模型级的，不是账号级的**。账号里另外 8 个模型有 1M 免费额度可用，
只要把单模型换成模型链，这个问题立刻消失。

### 1.2 8 个可用模型（服务器实测）

`response_format={"type":"json_object"}` 真实调用，全部通过：

| 模型 | HTTP | 首字耗时 | content 是否合法 JSON |
| --- | --- | --- | --- |
| deepseek-v4.1-flash | 200 | 2.63s | ✅ |
| glm-5.3 | 200 | 2.39s | ✅ |
| qwen3.8-max | 200 | 2.79s | ✅ |
| deepseek-v4-pro-0813 | 200 | 2.32s | ✅ |
| qwen3.8-2.4t-a95b | 200 | 3.71s | ✅ |
| kimi-k3 | 200 | 2.58s | ✅ |
| qwen3.8-27b | 200 | 2.24s | ✅ |
| qwen3.8-max-0902 | 200 | 3.89s | ✅ |

补充观察：`deepseek-v4.1-flash`、`deepseek-v4-pro-0813`、`kimi-k3` 在 `max_tokens` 很小时 `content` 为空
（推理内容占了预算）。应用不设 `max_tokens`，不受影响；但**这三者是推理模型，延迟波动会更大**，排链时需考虑。

### 1.3 三个连带缺陷

1. **分类失真**：`FailureCategoryClassifier.aiStatus()` 把 403 一律归为 `PROVIDER_4XX`，
   无法区分「密钥错」和「额度尽」，因此没有任何机制能对额度耗尽做出反应
   （枚举里其实已有 `QUOTA_EXHAUSTED`，但 4xx 路径走不到它）。
2. **健康探针有盲区**：`/api/system/health` 的 `ai-provider` 只判断 `hasAvailableProvider()`
   —— 即「是否配了 Key」。上一轮全部 AI 调用 403 失败时，它依然报 `UP`，把事故完全掩盖了。
3. **无降级可观测性**：没有「发生了多少次模型降级」「当前还有几个模型可用」的指标。

> 另注：`docs/ideation/2026-09-04-*.md` 的 **#18 号风险**（"提供者路由是第一个支持者策略，
> 无可用性过滤、无失败切换"）与本方案是同一件事，本方案一并闭环。

---

## 2. 方案设计

### 2.1 配置（新增，向后兼容）

```yaml
app:
  ai:
    bailian:
      model: ${BAILIAN_MODEL:qwen-plus}                    # 保留；链为空时作为单元素链
      model-chain: ${BAILIAN_MODEL_CHAIN:}                 # 新增，有序逗号分隔
      chain-quota-cooldown-seconds: ${AI_CHAIN_QUOTA_COOLDOWN_S:1800}   # 配额类失效冷却
      chain-transient-cooldown-seconds: ${AI_CHAIN_TRANSIENT_COOLDOWN_S:60}  # 瞬时故障冷却
```

**不复用 `BAILIAN_MODELS` 这个名字**：它是本项目历史上已废弃的僵尸配置（见 O-05 闭环记录），
而用户的 `.env.live-ai` 里至今残留着这个变量 —— 复用会被静默激活，产生意外行为。顺带清理该残留。

### 2.2 降级语义

```
取链 → 跳过处于冷却期的模型 → 依次尝试
        ├─ 成功                              → 返回（并记录实际服务模型）
        ├─ 配额耗尽 / 模型不存在              → 该模型进入长冷却（1800s），顺延下一个
        ├─ 超时 / 连接失败 / 5xx / 429 / 空响应 → 顺延下一个，短冷却（60s）
        └─ 密钥无效 / 请求参数非法            → 立即终止，不顺延（换模型也没用）
全部失败 → 返回聚合失败结果
```

**为什么要冷却**：额度耗尽不是瞬时故障。若不记状态，每个请求都要先把已死的模型挨个试一遍，
8 个模型全死时单次调用要白等 8 次网络往返（实测单次 2–4s，最坏叠加 20s+）。
冷却让后续请求直接跳过，把代价限制在第一次。

### 2.3 失败分类升级（隐私约束内）

在 `RestClientResponseException` 分支里**只提取机器可读的错误码**用于分类，
**绝不记录响应 body**（沿用现有隐私约束：4xx body 可能回显简历/JD 片段）：

| 响应体 `error.code` | 归类 | 是否顺延 |
| --- | --- | --- |
| `AllocationQuota.*` | `QUOTA_EXHAUSTED` | ✅ 且长冷却 |
| `Model.NotFound` / `InvalidParameter`（模型相关） | `PROVIDER_4XX` | ✅ 且长冷却（模型名错就该跳过） |
| `InvalidApiKey` / `Model.AccessDenied` | `PROVIDER_4XX` | ❌ 立即终止 |
| `Throttling.*` | `RATE_LIMITED` | ✅ 短冷却 |
| 其他 4xx | `PROVIDER_4XX` | ❌ |

### 2.4 健康探针修复

`/api/system/health` 的 `ai-provider` 判定改为「**链上至少一个模型未处于冷却期**」，
并在 `checks` 数组增补一项 `ai-model-chain`。

- `checks` 是列表，增加元素对前端契约**向后兼容**（无需改前端类型）。
- 效果：所有模型都失效时整体状态变为 `DEGRADED` —— 这正是上一轮那个盲区的解药。

### 2.5 可观测性

| 指标 | 用途 |
| --- | --- |
| `resume_ai_provider_calls{model=...}` | **已存在**，模型维度已就绪，无需改动 |
| `resume_ai_model_chain_fallbacks_total{from_model,to_model}` | 新增：降级次数与路径 |
| `resume_ai_model_chain_available` | 新增 gauge：当前可用（未冷却）模型数 |

### 2.6 审计准确性

`AiCallResult` 增加**可选** `modelCode` 字段：保留现有 `ok()/fail()` 签名，新增重载。
这样 `InterviewOperationSupport:200` 写入尝试记录的会是**实际服务的模型**，而不是链首。

> 已核实 `new AiCallResult(...)` 仅出现在该 record 自身的两个工厂方法内，
> 各组件读取散落在 5 处但都是访问器调用，因此加字段**不破坏任何调用点**。

---

## 3. 影响面

| 类型 | 文件 |
| --- | --- |
| 改动（核心） | `ai/provider/BailianAiProvider.java` |
| 改动（小） | `ai/provider/AiCallResult.java`（加可选字段）、`system/controller/SystemController.java`（判定 + 1 个 checks 项）、`common/observability/AppObservability.java`（2 个指标）、`application.yml` |
| 新增 | 模型链状态持有类（含冷却计时与线程安全）、错误码解析工具 |
| 配置 | 部署环境 `.env` 增加 `BAILIAN_MODEL_CHAIN`；清理废弃的 `BAILIAN_MODELS` |
| **不动** | `AiProvider` 接口、8 个调用方服务（JobGeneration / JobMaterialSelection / TaskExecution / Ats / Communication / Interview×3）、前端契约、数据库、Flyway |

**线程安全**：冷却状态会被 AI worker 的多线程并发访问（`TaskExecutionService` 有批量 worker），
状态容器必须用并发结构，且不能引入锁竞争。

---

## 4. 测试计划

**单元测试**（新增，不依赖真实网络）
1. 链首成功 → 只调用 1 次
2. 链首配额耗尽 → 顺延到第二个并成功
3. 全部失败 → 返回聚合失败，且归类为 `QUOTA_EXHAUSTED`
4. 冷却期内跳过 → 不再重复调用已死模型
5. 冷却到期 → 重新参与尝试（验证时间可控，用可注入时钟）
6. `InvalidApiKey` → 立即终止，不顺延
7. 空响应 / 非法 JSON → 顺延（若开关开启）
8. 链配置为空 → 退化为单模型行为（向后兼容）

**真实联调**：走一次 `JOB_GENERATION` 全链路，确认实际服务模型与日志一致。

**回归**：现有 AI 相关测试类必须全绿（`JobGenerationServiceTest`、`InterviewAiServiceTest` 等）。

---

## 5. 风险与规避

| 风险 | 规避 |
| --- | --- |
| 冷却状态在重启后丢失，重新付出一次探测代价 | 可接受（单次代价有限）；不做持久化以避免引入 DB 依赖 |
| 推理模型（deepseek/kimi）延迟波动大，拖慢链 | 链序上把它们放在结构化生成任务之后；必要时按任务类型分链（见待确认） |
| 顺延导致同一请求被多个模型处理，放大额度消耗 | 仅在失败时顺延，不并行广播；失败本身已消耗的 token 无法避免 |
| 健康检查变 DEGRADED 触发误告警 | 语义正确（确实不可用），配合新增告警规则按阈值触发 |
| 新增配置名与旧僵尸变量冲突 | 使用新名 `BAILIAN_MODEL_CHAIN` 并清理旧变量 |

---

## 6. 已确认决策（2026-09-23）

| 编号 | 决策点 | 结论 |
| --- | --- | --- |
| D1 | 调用策略 | **严格降级链**（首个成功即返回，失败顺延）；不做轮询分摊、不做额度优先级 |
| D2 | 链顺序 | 按实测延迟排序，延迟稳定的非推理模型在前，三个推理模型兜底 |
| D3 | 健康检查修复 | **做**（全链失效报 DEGRADED + 新增 `ai-model-chain` 检查项） |
| D4 | 清理僵尸变量 | **做**（本机 `.env.live-ai` 与服务器 `live-ai.env` / `app/api/.env`） |
| D5 | 审计记录写入实际服务模型 | **本次不做**（`AiCallResult` 保持原样） |
| D6 | 新增 Prometheus 告警规则 | **本次不做**（指标已产出，告警规则留待后续） |

链顺序（最终）：

```
qwen3.8-max → glm-5.3 → qwen3.8-27b → qwen3.8-2.4t-a95b → qwen3.8-max-0902
  → deepseek-v4.1-flash → deepseek-v4-pro-0813 → kimi-k3
```

## 7. 实施中发现并修复的缺陷

**限流被误判为额度耗尽。** 单元测试首次运行即失败：

```
BailianFailureClassifierTest.rateLimitAndServerError
  expected: <RATE_LIMITED> but was: <QUOTA_EXHAUSTED>
```

根因：百炼的限流错误码 `Throttling.RateQuota` 中含 `quota` 子串，
而额度判定先于限流判定执行，于是**瞬时的一次限流会把该模型长冷却 30 分钟**。
在链式调度下这会显著降低可用性（一个模型被无谓地关掉半小时）。

修复采取双重保险：
1. 调整判定顺序，限流关键字优先于额度关键字；
2. 把排除逻辑内聚到 `isQuotaExhausted()` 谓词内部 —— 不依赖调用方顺序，
   以后任何新调用方都不会再踩同一个陷阱。

已补回归测试 `throttlingCodeContainingQuotaIsNotTreatedAsExhausted`。

## 8. 变更记录

| 日期 | 变更 | 原因 |
| --- | --- | --- |
| 2026-09-23 | 初版方案 | 用户提供 8 个可用模型，要求做成模型链 |
