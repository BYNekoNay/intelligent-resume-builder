# 项目上下文

> 面向后续 AI 协作与新开发者的当前事实基线。开始需求分析、修复或改动前先阅读本文件，再以代码和当前运行文档为准。历史 `docs/01-13` 与 `docs/agent-tasks/` 只用于追溯，不覆盖本文。

## 1. 项目目标

智历是一个面向求职者的岗位定制简历平台。它不是“根据 JD 修改一份现有简历”的工具；核心是让用户维护一套长期可复用的职业资料库，再针对每一份 JD 选择事实素材、人工确认，并生成一份独立的岗位简历。

产品原则：

- 简历内容必须可追溯到用户确认的真实资料，AI 不能编造经历、技能、数据或成果。
- AI 只产生候选或草稿，用户确认后才会写入简历版本。
- 每个岗位简历及其历史版本独立保存；错误保存应通过恢复新版本和归档处理，而不是覆盖或删除历史证据。
- 联系方式由服务端合并，不能发送给模型；量化成果遵循用户选择的展示口径后才进入模型输入。
- 当前真实 AI 提供方是阿里云百炼兼容接口，不再使用 Mock 模型作为正常功能路径。

## 2. 核心用户流程

```text
个人档案 + 职业资料库
        |
        v
选择或粘贴 JD
        |
        v
规则预筛 + AI 选材任务
        |
        v
用户调整并确认选材快照
        |
        v
AI 结构化岗位简历草稿
        |
        v
用户逐项审核 / 编辑 / 确认
        |
        v
创建或更新独立岗位简历 -> 当前版本 -> 编辑、预览、PDF、投递、面试资产
```

### 资料库

- 个人档案：联系方式、个人简介、全局职业目标（目标职位、职级、行业、城市或工作方式、定位摘要）。
- 基础资料：工作、项目、教育、技能、证书、语言等。
- 深化资料：量化成果、管理/协作经历、单技能证据；它们通过关联工作或项目建立上下文。
- 使用偏好：`PREFERRED` 提高选材优先级，`EXCLUDED` 默认不发送给模型，本次手动必选可覆盖排除但不改变全局偏好。

### JD 与岗位简历

- JD 管理负责增删改、解析和“生成岗位简历”入口。
- 生成工作台先执行 `JOB_MATERIAL_SELECTION`，用户必须确认选材，才会启动 `JOB_GENERATION`。
- 同一 JD 再次生成时，可在新版确认页选择更新已有岗位简历或另建一份。
- 岗位简历绑定 JD；详情页的规则覆盖度只使用该绑定 JD。通用简历不显示 JD 覆盖评分入口。

### 版本规则

- 当前版本不能归档；归档版本不能设为当前。
- “恢复为新版本”复制历史内容为新的 `RESTORED` 版本并设为当前，原版本保持不变。
- 本阶段只做归档与恢复，不提供永久删除。

## 3. 技术结构

```text
web/          Vue 3 + TypeScript + Vite 的用户界面
server/       Spring Boot / Java 17 API、MySQL/Flyway、异步 AI Worker
pdf-service/  Node.js + Puppeteer 私有 PDF 渲染服务
scripts/      本地真实 AI、PDF 故障恢复、全流程验收脚本
monitoring/   Prometheus、Grafana 仪表盘与告警规则模板
deploy/       部署环境变量样例与镜像/运行文档
docs/         当前运行文档与历史设计资料
```

### 前端目录结构

```text
web/src/
├─ api/                 前端 API 契约；按认证、简历、职业资料等领域拆分
├─ components/resume/   简历编辑器的章节导航、卡片编辑、预览与设计面板
├─ composables/         可复用交互状态；`useResumeEditorDraft` 管理本地恢复草稿
├─ i18n/                中英文界面文案；页面不得新增硬编码用户文案
├─ layouts/              全局导航与登录后的账户入口
├─ stores/               Pinia 会话、当前用户等跨页状态
└─ views/                路由页面；`ResumeEditorView`、`AccountView` 等页面编排容器
```

### 后端模块

| 模块 | 职责 |
| --- | --- |
| `auth` | 注册、登录、JWT/刷新会话、当前用户资料，以及邮箱和密码修改 |
| `personalprofile` | 用户唯一个人档案与从现有简历导入建议 |
| `careermaterial` | 职业资料 CRUD、偏好、类型与关联校验 |
| `jobdescription` | JD CRUD、解析及岗位简历关联 |
| `ai` | 授权、配额、任务、Worker、百炼调用、两阶段选材与生成 |
| `resume` | 简历、版本、草稿确认、归档、恢复、编辑器数据 |
| `scoring` / `ats` | JD 覆盖度与 ATS 分析 |
| `export` | PDF 任务、私有下载、重试与存储 |
| `application` / `communication` / `interview` | 投递、沟通文案、面试资产与业务记录 |
| `common` | API 响应、异常、Trace ID、可观测性与跨模块基础能力 |

### 前端重点

- 页面集中在 `web/src/views/`，请求契约在 `web/src/api/`，全局文案在 `web/src/i18n/`。
- 简历编辑器由 `ResumeEditorView.vue` 编排，章节导航、条目卡片、预览和设计面板拆入 `web/src/components/resume/`。默认按当前章节单栏填写，预览为独立全页模式；模板选择可以在预览中打开。
- 编辑器共享同一章节注册表、`layout.sectionOrder` 和 JSON 映射，默认支持 14 个栏目：个人信息、职业目标、个人链接、工作、实习/志愿、技能、项目、教育、培训课程、证书、研究成果、奖项、语言和自定义模块。
- 编辑中的未保存内容按“用户 ID + 简历 ID + 正式版本”隔离到浏览器本地。恢复草稿必须由用户显式确认；创建新版本成功、主动丢弃或正式版本变化时清除草稿。
- `AccountView.vue` 是账户与个人资料入口。邮箱和密码仅在用户点击相应修改动作后以独立对话框输入；两项修改均要求当前密码，并在成功后撤销刷新会话、要求所有设备重新登录。
- AI 接口大多是异步任务：创建接口返回 `202 + taskId`，前端必须轮询 `GET /api/ai/tasks/{id}` 至 `SUCCESS` 或失败，不能把任务创建响应当作最终结果。
- 内联润色 `INLINE_OPTIMIZE` 仅针对可叙述字段返回候选文本，用户点击“采纳并写回”才修改编辑器；候选与原文相同会被过滤，AI 不得自动补充事实、数字、链接、机构或经历。

### AI 与隐私边界

- AI 授权版本当前为 `v1.1.0`，任务执行时校验任务范围和数据类别，撤销授权后不得继续执行。
- 岗位选材和岗位生成使用不可变的 JD、个人档案、资料快照，防止生成期间被编辑的数据混入。
- 资料选材默认最多向模型发送 60 条候选，模型最多推荐 12 条；用户确认后的总资料数最多 30 条。
- Prompt、模型原文、用户资料、联系方式、Token、密钥和用户 ID 不得写入日志、指标标签、测试报告或 Git。
- 修改登录邮箱和密码时，服务端必须校验当前密码、校验邮箱唯一性并撤销该用户的刷新会话；前端不得缓存或记录密码字段。

## 4. 数据与任务的关键关系

```text
user
 |- personal_profile (一人一份)
 |- career_material (可复用职业事实)
 |- job_description
 |- resume
 |    |- resume_version (currentVersionId 指向当前编辑起点)
 |
 `- ai_task
      |- JOB_MATERIAL_SELECTION -> 用户确认的资料快照
      `- JOB_GENERATION (parent_task_id 指向选材任务) -> 草稿/版本
```

- `resume_material_reference` 只记录最终草稿实际引用的资料，不应把所有候选资料标记为已使用。
- 结构化生成字段必须兼容编辑器：工作、项目、教育、技能、证书均使用标准字段名称；服务端需归一化常见别名并通过 Schema 校验。
- 历史 `_source` 仍兼容；新草稿优先使用 `_sources` 记录真实资料引用。

## 5. 本地运行与验证

本地验证不依赖 Docker。前置条件：Java 17、Maven、Node.js、运行中的 MySQL、`web/` 和 `pdf-service/` 已安装依赖。

```powershell
# 根目录，`.env` 放 MySQL 配置，`.env.live-ai` 放 BAILIAN_API_KEY
.\scripts\Start-LocalValidation.ps1

# Web 5173 / API 8080 / PDF 3001
.\scripts\Test-LocalFullFlow.ps1
.\scripts\Stop-LocalValidation.ps1
```

`Start-LocalValidation.ps1` 会从被 Git 忽略的 `.env.live-ai` 加载 `BAILIAN_*` 变量并启动真实百炼路径。手工重启 API 时也必须继承这些变量，否则 AI 任务会以提供方 4xx 失败。

常用验证：

```powershell
Set-Location server; mvn test
Set-Location ..\web; npm run build
Set-Location ..\pdf-service; npm run check; npm test
```

更完整的说明见 `docs/LOCAL_VALIDATION.md`。部署、镜像、回滚和 ECS 注销前备份见 `docs/DEPLOYMENT.md`。

## 6. 修改项目时的工作约束

1. 先确认当前用户流程和后端状态，再修改；不要依据历史 MVP 文档猜测现状。
2. 保持用户隔离：任何资料、JD、简历版本、任务和导出都必须归属当前用户。
3. 不要重新引入“选择一份已有简历作为 JD 生成目标”的旧入口；岗位简历默认由资料库加 JD 生成。
4. 不要把 AI 输出直接写入简历；生成、内联润色和资料补全都需要人工确认。
5. API 异步任务必须处理轮询、失败、重试和授权撤销；不要只判断 HTTP 202。
6. 现有工作区可能包含未提交的用户改动。只修改与任务直接相关的文件，绝不回退未知改动。
7. 密钥、密码、JWT、Cookie、数据库备份、真实用户简历和模型输入输出都不能提交或展示。

## 7. 当前文档优先级

1. 本文件：产品边界、当前架构和协作规则。
2. `README.md`：能力概览、快速启动和安全配置。
3. `docs/LOCAL_VALIDATION.md`：本地真实 AI 与 PDF 验证。
4. `docs/DEPLOYMENT.md`：构建、部署、回滚与服务器操作。
5. `docs/DEPLOYMENT_READINESS.md`：生产边界、观测和安全基线。
6. `docs/01-13`、`docs/agent-tasks/`：历史背景，不是当前实现契约。
