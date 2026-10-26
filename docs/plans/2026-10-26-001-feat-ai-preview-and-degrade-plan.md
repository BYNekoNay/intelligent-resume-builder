# 计划：AI 发送前预览（R32）与失败保留/降级（R33）

> 状态：**已批准执行**（2026-10-26）。本文是执行依据，验收标准逐条可测。
> 依据：`docs/plans/2026-10-20-001-feat-windows-offline-career-workspace-plan.md` 的 R32/R33，
> ADR-012 的 AE2 / AE6，以及 2026-10-26 的链路实测（见 §1 现状差距）。

## 1. 现状差距（实测，不是推测）

| 要求 | 现状 | 差距 |
| --- | --- | --- |
| R32 发送前展示**将要发送的确切内容** | 无 dry-run 能力。prompt 在 worker 里现场生成（`PromptTemplates` / `JobGenerationPromptBuilder`），任务创建时无法得到待发内容 | 缺「预览组装」与「预览 → 创建」的可校验绑定 |
| R32 默认**排除联系方式** | `JOB_GENERATION` / `JOB_MATERIAL_SELECTION` / `COMMUNICATION_GENERATE` / `ATS_ANALYSIS` / `INTERVIEW_COACH` **已排除**；`RESUME_OPTIMIZE` / `INLINE_OPTIMIZE` / `ACHIEVEMENT_GUIDANCE` / `MATERIAL_IMPORT` **原样 dump，无脱敏** | 4 类无脱敏 |
| R32 需**显式确认**；取消则一条不发 | 取消=不调 `create`，天然无写入（这条已满足，但缺少"看到的就是发出的"保证） | 需 token 绑定 |
| R33 **不自动重试** | `releaseFailed(retryable=true)` 在 `retryCount < 3` 时把任务打回 `PENDING`，worker 再领取并**再次调 provider** | **行为冲突，必须改** |
| R33 **不静默换供应商** | `callChain` 在模型链上逐个顺延，只写日志与指标 | 需把"换过模型"变成**可见事实** |
| R33 失败保留 prompt / 已选素材 / 草稿 | `input_snapshot_json` 本来就保留 prompt 与已选素材；失败不写 `result_json`、不建 `resume_version`、不落孤儿草稿 | **已满足**（本轮加断言钉住） |

## 2. 设计决策

| 编号 | 决策 | 理由 |
| --- | --- | --- |
| D-AI-1 | 预览与实际发送**共用同一个组装函数**（把 `BailianAiProvider.buildMessages` 抽成 `AiMessageAssembler`），不另写一份 | 两处各写一份必然漂移，"看到的"和"发出去的"会悄悄不一样 |
| D-AI-2 | 预览返回 `previewToken` = 载荷规范化后的 SHA-256（含 taskType + messages + 脱敏开关） | 预览后改了输入 → token 对不上 → 拒绝创建，这是"确切的"唯一可校验实现 |
| D-AI-3 | 创建任务时**必须**带 `previewToken`；缺失 → `AI_PREVIEW_REQUIRED`，不符 → `AI_PREVIEW_STALE`，两者都**不落库** | 不做"可选的确认"，否则等于没有 |
| D-AI-4 | 脱敏默认**排除邮箱与手机号**（`includeContact=false`），用户可显式勾选包含 | R32 原文要求；但**不改 URL/普通文本**，避免把简历里的项目链接也删掉影响质量 |
| D-AI-5 | 关闭自动重试：可重试失败也直接 `FAILED`，把 `retryable` 暴露给前端，重试只能由用户点 | R33 原文；且失败任务仍占配额，自动重试会让配额消耗对用户不可见 |
| D-AI-6 | 模型链顺延保留（同供应商内、配额驱动），但把每次尝试写进结果 `modelAttempts` | 直接禁用会让"某模型额度耗尽"时整条链路不可用；R33 要求的是**不静默**，不是不换 |

## 3. 实施批次与验收标准

### P1 · R32 后端（预览组装 + 脱敏 + token 绑定）

| AC | 验收标准（可测） |
| --- | --- |
| AC-R32-1 | `POST /api/ai/tasks/preview` 返回的 `messages` 与 worker 实际交给 provider 的 `messages` **逐字节相等**（同一 `AiMessageAssembler`，单测对同一 input 断言两次调用结果相等） |
| AC-R32-2 | 输入含 `alice@example.com` / `13800138000` 时，预览文本中**不出现**原始值，且响应 `redactions` 列出被替换的类别与次数；`includeContact=true` 时不脱敏 |
| AC-R32-3 | 不带 `previewToken` 创建任务 → 返回 `AI_PREVIEW_REQUIRED`，且 `ai_task` **零新增行** |
| AC-R32-4 | 预览后修改 input 再创建 → `AI_PREVIEW_STALE`，零新增行 |
| AC-R32-5 | 预览接口**不写库、不调 provider**（断言 repository 无新增、provider 零调用） |

### P2 · R32 前端（预览确认流）

| AC | 验收标准 |
| --- | --- |
| AC-R32-6 | 点击生成 → 先弹出预览（可看到完整待发文本与本轮脱敏摘要）→ 确认后才发起任务；取消则无任何请求发出（e2e 断言 `/api/ai/tasks` 未被调用） |
| AC-R32-7 | 预览层可勾选「包含联系方式」，勾选后重新预览，`redactions` 为空 |

### P3 · R33 后端（不自动重试 + 换模型可见 + 保留断言）

| AC | 验收标准 |
| --- | --- |
| AC-R33-1 | provider 返回可重试失败 → 任务**直接变 `FAILED`**，`retry_count` 不再增加（改写 `TaskLeaseServiceTest` 里与新要求冲突的两条用例） |
| AC-R33-2 | 失败任务的 `input_snapshot_json` 仍可读出 prompt 与已选素材；`result_json` 为空；`resume_version` 无新增；无孤儿草稿 |
| AC-R33-3 | 模型链顺延后，任务/结果里出现 `modelAttempts`（每次模型名与失败类别），前端可见 |
| AC-R33-4 | 用户手动重试仍可用（`POST /api/ai/tasks/{id}/retry`），且只在 `FAILED` 时允许 |

### P4 · 阶段六收尾（Electron 窗口与安装包）

| AC | 验收标准 |
| --- | --- |
| AC-SHELL-1 | `desktop-shell` 能在有图形会话的 Windows 上 `npm install && npm start` 打开窗口并加载工作区（本机无头，交由人工确认，**不算已验证**） |
| AC-SHELL-2 | `electron-builder` 能产出 NSIS 安装包，安装后启动能拉起 jar 并进入登录后的工作区 |
| AC-SHELL-3 | 安装包内**不含**任何密钥文件（`ai-credentials/`、`secrets.env`、`.env`） |

## 4. 明确不做

- R32 不覆盖"任务执行中再次确认"的场景（任务是一次性提交，不存在中途改内容）。
- R33 不实现"失败自动降级到其他供应商"（D7 明确不静默切换；本地规则降级只存在于 ATS，且已有）。
- `inline_optimization_record` 表仍无写入者 —— 本轮不新增写入路径（"失败保留当前草稿"由 `ai_task` 快照 + 前端编辑器状态满足，草稿历史是另一件事）。

## 5. 风险

| 风险 | 处置 |
| --- | --- |
| 强制 `previewToken` 会打破既有客户端/测试 | 一次性改完并同步测试；这本身就是"必须显式确认"的落地代价，不设后门开关 |
| 脱敏过度导致生成质量下降 | 只脱敏邮箱/手机号两类，不动 URL 与正文 |
| 关闭自动重试后瞬时抖动会变成用户可见失败 | 失败响应里给出 `retryable`，前端提供一键重试；这符合 R33「由人决定」的口径 |

---

## 6. 执行状态（2026-10-26）

| AC | 状态 | 守护用例 |
| --- | --- | --- |
| AC-R32-1 | ✅ | `AiPromptParityTest`（provider 路径 == 预览路径，含脱敏开关两态） |
| AC-R32-2 | ✅ | `ContactRedactorTest` + `AiPromptParityTest#defaultExcludesContactAndKeepsEverythingElse` |
| AC-R32-3 | ✅ | `AiTaskPreviewProtocolIT#createWithoutTokenRejected`（含"不落库"断言） |
| AC-R32-4 | ✅ | `AiTaskPreviewProtocolIT#staleTokenRejected`（含"不落库"断言） |
| AC-R32-5 | ✅ | `AiTaskPreviewProtocolIT#previewIsReadOnly` |
| AC-R32-6 | ⬜ 待做 | 前端预览确认流（P2） |
| AC-R32-7 | ⬜ 待做 | 前端「包含联系方式」开关（P2） |
| AC-R33-1 | ✅ | `TaskLeaseServiceTest#releaseFailed_retryable_stillMarksFailed`（断言 FAILED，改回 PENDING 即红） |
| AC-R33-2 | ✅ | 既有行为已满足，由 `AiTaskControllerIT`/`TaskLeaseServiceTest` 覆盖保留语义 |
| AC-R33-3 | ✅ | `BailianAiProviderChainTest#fallbackSuccessRecordsModelAttempts` + `AiTaskResults` 落结果 |
| AC-R33-4 | ✅ | 手动重试入口未改动（`InlineOptimizeController#retry`，仅 FAILED 可重试） |
| AC-SHELL-1/2/3 | ⬜ 待做 | Electron 窗口验证、安装包、包内无密钥检查（P4） |

**本轮范围说明**：R32 的强制校验落在**通用任务端点**（`POST /api/ai/tasks`）。
`JOB_GENERATION` / `JOB_MATERIAL_SELECTION` / `COMMUNICATION_GENERATE` / `ATS_ANALYSIS` / `INTERVIEW_COACH`
走各自的领域端点，且本来就已排除联系方式、本身带确认步骤 —— 把它们接进预览协议是下一批的事（不在本轮声称完成）。
