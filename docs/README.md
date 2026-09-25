# 项目文档索引

> 文档树总入口。目录约定：
>
> | 位置 | 性质 |
> | --- | --- |
> | `docs/` 根 | **当前生效**的操作手册，随代码同步更新 |
> | `docs/{plans,reviews,research,ideation,diagrams}/` | 按产出类型归类的项目档案（不可变为主，只附录） |
> | `docs/decisions/` | **决策登记册**：ADR（不可变，纠正靠追加新 ADR）+ 悬而未决登记册（只追加 + 就地关闭） |
> | `docs/archive/` | 从当前手册中析出的历史日志 |
> | `docs/01-13/`、`docs/agent-tasks/` | 早期 MVP 历史资料，**只作追溯**，不是当前实现契约 |

---

## 1. 当前入口（需要操作项目时优先读）

| 文档 | 用途 |
| --- | --- |
| [项目上下文](../PROJECT_CONTEXT.md) | 产品目标、真实流程、模块职责、数据边界与协作约束；后续 AI 与新开发者的首读入口 |
| [根目录 README](../README.md) | 项目能力、环境配置与快速开始 |
| [本地验证指南](./LOCAL_VALIDATION.md) | 不依赖 Docker 的本地启动、测试与真实百炼 AI 验收 |
| [部署运行手册](./DEPLOYMENT.md) | Docker 路线：GitHub Actions 构建、阿里云 ACR、ECS 部署、验证、回滚与注销前备份 |
| [直接上传部署手册（非 Docker）](./DEPLOYMENT_DIRECT.md) | **当前测试环境**（`101.35.239.218:8088`）的直接上传部署：环境安装、与同机另一项目共存的约束、Chromium 预置、验收清单与已知坑 |
| [GitHub CI/CD 流水线](./CI.md) | PR/主分支持续集成、手动 ACR 镜像发布、Secrets 与分支保护配置 |
| [部署状态说明](./DEPLOYMENT_READINESS.md) | 运行边界、可观测性与安全基线 |
| [云端镜像发布（迁移入口）](./CLOUD_IMAGE_RELEASE.md) | 旧发布方式的跳转占位 |

---

## 2. 分类产出物

### 2.1 plans/ —— 方案、PRD 与任务卡

| 文档 | 内容 |
| --- | --- |
| [2026-07-27-002-remaining-audit-closure](./plans/2026-07-27-002-remaining-audit-closure.md) | 剩余审计项闭环计划与回归证据 |
| [2026-08-10-001-design-optimization](./plans/2026-08-10-001-design-optimization.md) | 求职工作区设计优化 |
| [2026-08-11-001-review-remediation](./plans/2026-08-11-001-review-remediation.md) | 求职工作区评审问题整改 |
| [2026-08-11-002-fix-resume-flow-integrity-plan](./plans/2026-08-11-002-fix-resume-flow-integrity-plan.md) | 简历流程完整性修复计划 |
| [2026-08-31-003-interview-service-refactor-plan](./plans/2026-08-31-003-interview-service-refactor-plan.md) | InterviewService 上帝类拆分任务卡 |
| [2026-08-31-004-prd](./plans/2026-08-31-004-prd.md) | 求职闭环 4 功能增强 · 增量 PRD |
| [2026-08-31-004-design](./plans/2026-08-31-004-design.md) | 求职闭环 4 功能增强 · 系统设计与任务分解 |
| [2026-09-01-001-optimization-diagnosis](./plans/2026-09-01-001-optimization-diagnosis.md) | 优化诊断报告 |
| [2026-09-06-001-review](./plans/2026-09-06-001-review.md) | 102 项改动提交前逐文件架构审查 |
| [2026-09-23-001-direct-upload-deployment](./plans/2026-09-23-001-direct-upload-deployment.md) | 直接上传部署方案与已确认决策 |
| [2026-09-23-002-bailian-model-chain](./plans/2026-09-23-002-bailian-model-chain.md) | 百炼模型链方案：8 模型实测、链式调度设计、已确认决策与上线后发现的缺陷 |
| [2026-09-23-003-known-issues-remediation](./plans/2026-09-23-003-known-issues-remediation.md) | 已知问题系统梳理：复现证据、根因与影响范围、修改方案、S1~S6 实施计划 |
| [2026-09-24-001-browser-e2e-test-plan](./plans/2026-09-24-001-browser-e2e-test-plan.md) | **浏览器端端到端测试计划**（供具备视觉与操作能力的 AI 执行）：种子数据包、8 组用例、已知缺陷清单、严格回报格式 |

### 2.2 reviews/ —— 审计、评审与实施报告

| 文档 | 内容 |
| --- | --- |
| [2026-07-27-全项目实现一致性与质量审计](./reviews/2026-07-27-全项目实现一致性与质量审计.md) | 全项目实现一致性与质量审计 |
| [2026-07-27-remaining-audit-closure-report](./reviews/2026-07-27-remaining-audit-closure-report.md) | 剩余审计项闭环报告 |
| [2026-07-28-agent-backend-boundaries-review](./reviews/2026-07-28-agent-backend-boundaries-review.md) | 后端边界回归审查 |
| [2026-07-28-ai-interview-implementation-report](./reviews/2026-07-28-ai-interview-implementation-report.md) | AI 模拟面试完整闭环实施报告（**面试流程的权威结论**） |
| [2026-07-28-interview-flow-independent-review](./reviews/2026-07-28-interview-flow-independent-review.md) | 模拟面试流程独立审查（增量复审）；文首附编码修复说明 |
| [2026-07-29-ats-ai-analysis-report](./reviews/2026-07-29-ats-ai-analysis-report.md) | ATS 规则检查接入 AI 深度分析 |
| [2026-07-29-career-material-workspace-report](./reviews/2026-07-29-career-material-workspace-report.md) | 资料库三栏工作区实施 |
| [2026-07-29-communication-ai-generation-report](./reviews/2026-07-29-communication-ai-generation-report.md) | 岗位沟通文案 AI 生成 |
| [2026-08-11-business-flow-audit](./reviews/2026-08-11-business-flow-audit.md) | 业务流程与代码审查 |
| [2026-08-11-code-review](./reviews/2026-08-11-code-review.md) | 代码评审结果 |
| [2026-09-24-browser-e2e-report-triage](./reviews/2026-09-24-browser-e2e-report-triage.md) | 浏览器端测试报告的独立核实与处置（含 2 处判定修正） |
| [ats-reasoning-eval/](./reviews/2026-09-24-ats-reasoning-eval/E5-final-report.md) | **ATS 推理开关对比实验**：预注册非劣性检验、E1 rubric、判定表定稿、最终报告（结局 ③ 证据不足） |
| [2026-09-23-remote-ai-smoke-report](./reviews/2026-09-23-remote-ai-smoke-report.md) | 远程 AI 全链路冒烟：7 类 AI 任务实测结果、模型链线上降级证据、发现的缺陷与待决策项 |
| [agent-frontend-regression-review](./reviews/agent-frontend-regression-review.md) | 前端回归审查 |
| [agent-testing-evidence-review](./reviews/agent-testing-evidence-review.md) | 测试与证据回归审查 |

### 2.3 decisions/ —— 决策登记册（ADR 与悬而未决项）

| 文档 | 内容 |
| --- | --- |
| [README](./decisions/README.md) | 目录约定：ADR 不可变、登记册只追加 + 就地关闭 |
| [OPEN-DECISIONS](./decisions/OPEN-DECISIONS.md) | 悬而未决登记册（I2 领取策略 / I3 生成耗时 / I4 告警阈值等） |
| [ADR-001](./decisions/ADR-001-bailian-quota-is-per-model.md) | 百炼免费额度按**模型**维度独立计量 |
| [ADR-002](./decisions/ADR-002-model-chain-fallback-with-total-budget.md) | 模型链采用严格降级 + 总时间预算 |
| [ADR-003](./decisions/ADR-003-provenance-marker-exclusivity-scope.md) | 溯源标记互斥只适用于要求溯源的节点 |
| [ADR-004](./decisions/ADR-004-ai-poll-window-aligned-with-chain-budget.md) | 前端 AI 轮询窗口对齐链路总预算 |
| [ADR-005](./decisions/ADR-005-disable-model-reasoning-for-generation.md) | 对生成类任务关闭模型推理（477s → 7s） |
| [ADR-006](./decisions/ADR-006-keep-reasoning-for-ats-analysis.md) | 保留 ATS_ANALYSIS 推理（本轮实验未能回答该问题） |

### 2.4 research/ —— 调研与对标

| 文档 | 内容 |
| --- | --- |
| [2026-07-27-resume-editor-ux-benchmark](./research/2026-07-27-resume-editor-ux-benchmark.md) | 简历编写器 UX 竞品调研（官方一手资料） |
| [2026-07-27-mature-resume-editor-patterns](./research/2026-07-27-mature-resume-editor-patterns.md) | 成熟简历编辑器模式补充调研 |
| [2026-07-27-ats-friendly-resume-templates](./research/2026-07-27-ats-friendly-resume-templates.md) | ATS 友好简历模板调研 |
| [2026-07-28-job-application-product-benchmark](./research/2026-07-28-job-application-product-benchmark.md) | 求职工作流竞品研究与采用记录 |

### 2.5 ideation/ —— 优化构想与错误审计

| 文档 | 内容 |
| --- | --- |
| [2026-09-04-project-optimization-ideation](./ideation/2026-09-04-project-optimization-ideation.md) | 已闭环缺陷清单、仍存在的缺口与后续优化候选（当前唯一一份） |

### 2.6 diagrams/ —— 图表源文件

| 文件 | 内容 |
| --- | --- |
| [class-diagram.mermaid](./diagrams/class-diagram.mermaid) | 领域类图 |
| [sequence-diagram.mermaid](./diagrams/sequence-diagram.mermaid) | 关键流程时序图 |

### 2.7 archive/ —— 历史归档

| 文档 | 内容 |
| --- | --- |
| [browser-audit-log-2026-09](./archive/browser-audit-log-2026-09.md) | 2026-09-03 ~ 09-04 浏览器手工回归与逐轮审计日志（从 `LOCAL_VALIDATION.md` 析出） |

---

## 3. 早期 MVP 历史资料

`01` 至 `13` 以及 `agent-tasks/` 保存了早期 MVP 的需求、设计与实施记录，可用于追溯决策背景。
**它们不是当前操作手册，不能覆盖当前代码、根目录 README 或第 1 节的文档。**

### 3.1 项目规划与需求

| 文档 | 内容 |
| --- | --- |
| [01-项目开发总计划](./01-项目开发总计划.md) | 范围、阶段与 M1/M2 边界 |
| [02-需求规格说明书](./02-需求规格说明书.md) | 功能与非功能需求 |
| [06-开发任务拆解与里程碑](./06-开发任务拆解与里程碑.md) | 任务分解与里程碑 |

### 3.2 系统设计

| 文档 | 内容 |
| --- | --- |
| [03-系统架构设计说明书](./03-系统架构设计说明书.md) | 分层架构与模块职责 |
| [04-数据库设计说明书](./04-数据库设计说明书.md) | 表结构与关系 |
| [05-接口设计说明书](./05-接口设计说明书.md) | API 契约 |
| [11-新增智能能力需求与设计补充](./11-新增智能能力需求与设计补充.md) | AI 能力补充设计 |

### 3.3 质量与协作

| 文档 | 内容 |
| --- | --- |
| [07-测试计划与验收说明书](./07-测试计划与验收说明书.md) | 测试策略与验收标准 |
| [08-部署与运维说明书](./08-部署与运维说明书.md) | 早期部署运维设计（已被 `DEPLOYMENT*.md` 取代） |
| [09-风险管理与质量保障说明书](./09-风险管理与质量保障说明书.md) | 风险与质量保障 |
| [10-编码规范与协作流程](./10-编码规范与协作流程.md) | 编码规范 |
| [12-面向 AI 代理的开发流程与交付规范](./12-面向AI代理的开发流程与交付规范.md) | AI 代理交付规范 |

### 3.4 MVP 任务卡

| 文档 | 内容 |
| --- | --- |
| [13-MVP实施契约与任务卡](./13-MVP实施契约与任务卡.md) | MVP 实施契约总纲 |
| [agent-tasks/README](./agent-tasks/README.md) | 任务卡索引与权威顺序链 |
| [T00-通用执行前置条件](./agent-tasks/T00-通用执行前置条件.md) | 所有 Tnn 的共用前置 |
| [T01](./agent-tasks/T01-基础数据库与迁移.md) / [T02](./agent-tasks/T02-认证会话.md) / [T03](./agent-tasks/T03-简历与版本.md) / [T04](./agent-tasks/T04-职业资料.md) / [T05](./agent-tasks/T05-JD管理.md) | 基础域任务卡 |
| [T06](./agent-tasks/T06-AI同意与任务框架.md) / [T07](./agent-tasks/T07-岗位定制生成.md) / [T08](./agent-tasks/T08-来源确认与版本落地.md) | AI 闭环任务卡 |
| [T09](./agent-tasks/T09-JD规则覆盖度.md) / [T10](./agent-tasks/T10-私有PDF导出.md) / [T11](./agent-tasks/T11-MVP端到端验收.md) | 分析、导出与验收任务卡 |
| [TEMPLATE](./agent-tasks/TEMPLATE.md) | Tnn 操作手册骨架模板 |
| [T11 验收证据](./agent-tasks/evidence/T11-2026-07-12/report.md) | MVP 端到端验收进度报告 |

---

## 4. 维护原则

1. **当前操作流程只更新第 1 节列出的文档**，不要写进历史资料。
2. `plans/`、`reviews/`、`research/`、`ideation/` 以**附录式追加**为主；结论变化时新开一份并交叉引用，不要覆盖原结论。
3. `decisions/` 下 ADR **正文不可改写**：纠正必须追加新 ADR 并给旧 ADR 标 `Superseded by`；悬而未决登记册**只追加 + 就地关闭**，RESOLVED 时补 `Resolution` 字段，不删原条目。
3. 追加式的逐轮审计日志**不要写进当前手册**，放进 `archive/`，并保持单一时间线。
4. 历史资料如需更新，仅用于保留决策背景，并在文首明确其历史状态。
5. 所有文档统一 **UTF-8 + LF**（见根目录 `.gitattributes`）。
6. 密钥、Token、环境文件、数据库备份与 TLS 私钥不得写入文档或提交到 Git。

> 本地个人文档（如 `docs/实习面试项目应对手册.md`）已被 `.gitignore` 排除，不参与版本控制，因此不在本索引中建立链接。
