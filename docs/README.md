# 项目文档索引

本文档目录用于支撑 `intelligent-resume-builder` 项目的软件工程开发期工作，覆盖需求、设计、开发、测试、部署、风险、协作等核心环节。

## 文档清单

### 1. 项目与规划

- [01-项目开发总计划.md](D:/codegitee/intelligent-resume-builder/docs/01-%E9%A1%B9%E7%9B%AE%E5%BC%80%E5%8F%91%E6%80%BB%E8%AE%A1%E5%88%92.md)
  - 项目目标、范围边界、阶段划分、里程碑和验收口径

- [06-开发任务拆解与里程碑.md](D:/codegitee/intelligent-resume-builder/docs/06-%E5%BC%80%E5%8F%91%E4%BB%BB%E5%8A%A1%E6%8B%86%E8%A7%A3%E4%B8%8E%E9%87%8C%E7%A8%8B%E7%A2%91.md)
  - 按阶段、模块、优先级拆分开发工作

### 2. 需求与设计

- [02-需求规格说明书.md](D:/codegitee/intelligent-resume-builder/docs/02-%E9%9C%80%E6%B1%82%E8%A7%84%E6%A0%BC%E8%AF%B4%E6%98%8E%E4%B9%A6.md)
  - 用户角色、业务流程、功能需求、非功能需求、约束条件

- [03-系统架构设计说明书.md](D:/codegitee/intelligent-resume-builder/docs/03-%E7%B3%BB%E7%BB%9F%E6%9E%B6%E6%9E%84%E8%AE%BE%E8%AE%A1%E8%AF%B4%E6%98%8E%E4%B9%A6.md)
  - 总体架构、模块职责、技术选型、演进策略

- [04-数据库设计说明书.md](D:/codegitee/intelligent-resume-builder/docs/04-%E6%95%B0%E6%8D%AE%E5%BA%93%E8%AE%BE%E8%AE%A1%E8%AF%B4%E6%98%8E%E4%B9%A6.md)
  - 核心表设计、字段说明、索引建议、数据关系

- [05-接口设计说明书.md](D:/codegitee/intelligent-resume-builder/docs/05-%E6%8E%A5%E5%8F%A3%E8%AE%BE%E8%AE%A1%E8%AF%B4%E6%98%8E%E4%B9%A6.md)
  - 前后端联调用的 API 设计与接口约定

- [11-新增智能能力需求与设计补充.md](D:/codegitee/intelligent-resume-builder/docs/11-%E6%96%B0%E5%A2%9E%E6%99%BA%E8%83%BD%E8%83%BD%E5%8A%9B%E9%9C%80%E6%B1%82%E4%B8%8E%E8%AE%BE%E8%AE%A1%E8%A1%A5%E5%85%85.md)
  - 职业资料库、岗位定制生成、成果量化、ATS 体检、投递管理和 AI 面试等增量能力

### 3. 测试与发布

- [07-测试计划与验收说明书.md](D:/codegitee/intelligent-resume-builder/docs/07-%E6%B5%8B%E8%AF%95%E8%AE%A1%E5%88%92%E4%B8%8E%E9%AA%8C%E6%94%B6%E8%AF%B4%E6%98%8E%E4%B9%A6.md)
  - 测试范围、测试策略、核心测试场景、验收标准

- [08-部署与运维说明书.md](D:/codegitee/intelligent-resume-builder/docs/08-%E9%83%A8%E7%BD%B2%E4%B8%8E%E8%BF%90%E7%BB%B4%E8%AF%B4%E6%98%8E%E4%B9%A6.md)
  - 环境规划、部署流程、配置策略、监控与故障处理

### 4. 质量与协作

- [09-风险管理与质量保障说明书.md](D:/codegitee/intelligent-resume-builder/docs/09-%E9%A3%8E%E9%99%A9%E7%AE%A1%E7%90%86%E4%B8%8E%E8%B4%A8%E9%87%8F%E4%BF%9D%E9%9A%9C%E8%AF%B4%E6%98%8E%E4%B9%A6.md)
  - 项目风险识别、应对措施、质量门禁

- [10-编码规范与协作流程.md](D:/codegitee/intelligent-resume-builder/docs/10-%E7%BC%96%E7%A0%81%E8%A7%84%E8%8C%83%E4%B8%8E%E5%8D%8F%E4%BD%9C%E6%B5%81%E7%A8%8B.md)
  - 分支规范、提交规范、代码规范、评审流程、文档维护要求

- [12-面向AI代理的开发流程与交付规范.md](D:/codegitee/intelligent-resume-builder/docs/12-%E9%9D%A2%E5%90%91AI%E4%BB%A3%E7%90%86%E7%9A%84%E5%BC%80%E5%8F%91%E6%B5%81%E7%A8%8B%E4%B8%8E%E4%BA%A4%E4%BB%98%E8%A7%84%E8%8C%83.md)
  - 面向低能力代码代理的任务卡、执行循环、停止条件和交付报告规范

## 使用建议

建议按以下顺序使用这些文档：

1. 先阅读 `01` 和 `02`，统一目标和范围
2. 再阅读 `03`、`04`、`05`，完成系统设计与开发准备
3. 按 `06` 组织实际开发与迭代
4. 开发期间同步执行 `07`、`08`、`09`、`10`

## 代理任务执行手册

当由 AI 代码代理执行实现时,在阅读完 `01..10` 之后,请按以下顺序查阅:

1. [docs/13-MVP实施契约与任务卡.md](./13-MVP实施契约与任务卡.md) — 任务卡与契约层
2. [docs/agent-tasks/README.md](./agent-tasks/README.md) — 操作手册索引
3. [docs/agent-tasks/T00-通用执行前置条件.md](./agent-tasks/T00-通用执行前置条件.md) — 通用约定与禁止项
4. `docs/agent-tasks/Tnn-<名称>.md` — 本任务卡对应的 step-by-step 操作手册

文档权威顺序链:**`01..10 → 13 §5 → agent-tasks/T00 → agent-tasks/Tnn`**。

操作手册对应表(与 `13 §5` 的 T01–T11 一一对应):

| 任务卡 | 操作手册 |
| --- | --- |
| T01 基础数据库与迁移 | [T01-基础数据库与迁移.md](./agent-tasks/T01-基础数据库与迁移.md) |
| T02 认证会话 | [T02-认证会话.md](./agent-tasks/T02-认证会话.md) |
| T03 简历与版本 | [T03-简历与版本.md](./agent-tasks/T03-简历与版本.md) |
| T04 职业资料 | [T04-职业资料.md](./agent-tasks/T04-职业资料.md) |
| T05 JD 管理 | [T05-JD管理.md](./agent-tasks/T05-JD管理.md) |
| T06 AI 同意与任务框架 | [T06-AI同意与任务框架.md](./agent-tasks/T06-AI同意与任务框架.md) |
| T07 岗位定制生成 | [T07-岗位定制生成.md](./agent-tasks/T07-岗位定制生成.md) |
| T08 来源确认与版本落地 | [T08-来源确认与版本落地.md](./agent-tasks/T08-来源确认与版本落地.md) |
| T09 JD 规则覆盖度 | [T09-JD规则覆盖度.md](./agent-tasks/T09-JD规则覆盖度.md) |
| T10 私有 PDF 导出 | [T10-私有PDF导出.md](./agent-tasks/T10-私有PDF导出.md) |
| T11 MVP 端到端验收 | [T11-MVP端到端验收.md](./agent-tasks/T11-MVP端到端验收.md) |

## 补充说明

- [archive/project-plan.md](D:/codegitee/intelligent-resume-builder/docs/archive/project-plan.md) 是已废弃的早期英文计划，只保留历史背景，禁止作为实现依据
- 本目录中的中文文档为当前推荐执行版本
