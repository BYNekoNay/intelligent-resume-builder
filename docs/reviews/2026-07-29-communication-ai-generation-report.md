# 岗位沟通文案 AI 生成功能实施报告

日期：2026-07-29

## 执行摘要

岗位沟通文案现已提供两个明确入口：`AI 生成文案` 和 `模板生成`。AI 入口通过现有 `ai_task`、授权、配额和 Worker 链路调用 Provider；模板入口继续同步使用本地规则，不会创建 AI 任务。AI 失败不会自动伪装成模板结果，前端展示失败原因并提供授权、重试或手动模板切换。

本轮未新增数据库表或迁移，未修改导航、认证或投递管理契约。

## 改动文件

后端：

- `server/src/main/java/com/intelligentresume/communication/controller/CommunicationController.java`：新增 `POST /api/communications/ai-generate`，强制 `Idempotency-Key`。
- `server/src/main/java/com/intelligentresume/communication/service/CommunicationService.java`：服务端加载并校验简历版本/JD，创建 `COMMUNICATION_GENERATE` 任务；模板生成支持中英文并返回来源。
- `server/src/main/java/com/intelligentresume/communication/service/CommunicationAiService.java`：Provider 调用、一次契约修复、结构化结果校验、草稿幂等持久化。
- `server/src/main/java/com/intelligentresume/communication/service/CommunicationAiPromptBuilder.java`：任务快照裁剪、敏感信息脱敏、不可信数据边界和语言约束。
- `server/src/main/java/com/intelligentresume/communication/service/CommunicationAiResultValidator.java`：`subject/body` 条件 Schema、长度和语言漂移校验。
- `server/src/main/java/com/intelligentresume/ai/task/controller/AiTaskController.java`、`AiTaskService.java`：禁止从通用任务接口伪造通信快照；通信任务授权类别固定为 `RESUME`、`JOB_DESCRIPTION`。
- `server/src/main/java/com/intelligentresume/ai/worker/TaskExecutionService.java`：增加通信专用执行分支及失败处理。
- `server/src/main/java/com/intelligentresume/communication/dto/GenerateCommunicationRequest.java`、`CommunicationResponse.java`、`CommunicationDraftRepository.java` 及通信领域类型：扩展输出语言、来源和幂等契约。

前端：

- `web/src/views/CommunicationView.vue`：双按钮、AI 任务轮询、90 秒前台等待、URL `taskId` 恢复、授权/配额/失败/重试/模板切换、来源徽标和移动端纵向布局。
- `web/src/api/communication.ts`、`web/src/api/ai.ts`：通信 API、任务类型和结果类型。
- `web/src/i18n/index.ts`：中英文 AI、模板、授权和失败文案。
- `web/e2e/workflow.spec.ts`、`web/e2e/local-services.spec.ts`：模板流程、AI 任务流程及本地服务测试入口更新。

测试：

- `server/src/test/java/com/intelligentresume/communication/controller/CommunicationControllerIT.java`
- `server/src/test/java/com/intelligentresume/communication/service/CommunicationAiServiceTest.java`
- `server/src/test/java/com/intelligentresume/ai/worker/TaskExecutionServiceTest.java`
- `server/src/test/java/com/intelligentresume/ai/provider/BailianAiProviderLiveIT.java`（仅 `BAILIAN_LIVE_TEST=true` 启用）

## API 契约

### 模板生成

`POST /api/communications/generate`

请求增加可选 `outputLanguage`：`ZH_CN`（默认）或 `EN`。响应保留原字段，并增加：

```json
{"generationSource":"TEMPLATE"}
```

### AI 生成

`POST /api/communications/ai-generate`

必须带 `Idempotency-Key`，成功返回 HTTP `202` 和现有 `AiTaskStatusResponse`，任务类型为 `COMMUNICATION_GENERATE`。任务结果包含 `type`、`subject`、`body`、`draft`、`generationSource=AI`、`communicationDraftId`、资源 ID、Prompt/Schema 版本及 Provider 请求 ID。

任务查询和重试继续使用：

- `GET /api/ai/tasks/{taskId}`
- `POST /api/ai/tasks/{taskId}/retry`

## 验证命令与精确结果

| 命令 | 结果 |
|---|---|
| `mvn -q -Dtest=CommunicationAiServiceTest,CommunicationControllerIT test` | 通过 |
| `mvn -q -Dtest=CommunicationAiServiceTest,CommunicationControllerIT,TaskExecutionServiceTest test` | 通过 |
| `mvn test` | 416 tests, 0 failures, 0 errors, 3 skipped；BUILD SUCCESS |
| `npm run build` | i18n guard 36 Vue 文件/2 locales 通过；`vue-tsc -b` 通过；Vite 1744 modules 构建通过 |
| `npx playwright test e2e/workflow.spec.ts --grep "communication"` | 2 passed（模板流程、AI PENDING/SUCCESS 流程；桌面视口） |
| `npx playwright test e2e/workflow.spec.ts --grep "communication"`（加入 390×844 断言后的重跑） | 2 passed；模板流程、AI PENDING/SUCCESS 流程通过，包含 390×844 无水平溢出断言 |
| `BAILIAN_LIVE_TEST=true mvn -Dtest=BailianAiProviderLiveIT test` | 未执行，当前会话未提供真实百炼 API Key |

## 已验证的行为

- 模板按钮只调用 `/api/communications/generate`，不会创建 AI 任务。
- AI 按钮调用 `/api/communications/ai-generate`，发送稳定幂等键并轮询任务。
- 相同幂等键和相同快照复用同一任务；跨用户资源在授权检查前返回 `404`。
- Worker 使用通信专用服务，Provider 输出必须经过严格 DTO/语言校验；首次不合法响应最多触发一次同语言修复。
- AI 成功草稿写入现有 `communication_draft`，重复执行按精确草稿查询避免重复业务记录。
- AI 失败不会自动替换为模板；前端可授权、重试或主动使用模板。
- 中英文模板和 AI Prompt 均按界面语言传递，邮件主题仅在 `EMAIL` 类型生成。
- 任务 ID 写入 URL，刷新后可恢复轮询；切换模板会停止轮询并忽略迟到 AI 响应。

## 未验证风险与后续建议

- 真实百炼调用未执行，Provider 的实际返回形态、延迟和费用仍需在配置 API Key 后运行 live gate 验证。
- 通信模块 E2E 已在桌面和 390×844 视口完成；更广泛的全站 E2E 未在本轮执行。
- 当前通用 AI 任务状态接口仍沿用共享响应结构，通信结果细节放在 `resultJson` 中；如后续需要更强类型，可增加独立只读 DTO，但不属于本轮必要范围。
- 旧工作区存在其他模块的既有未提交改动，本报告仅覆盖本轮通信功能相关文件。
