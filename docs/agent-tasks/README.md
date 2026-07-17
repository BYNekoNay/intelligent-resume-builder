# 代理任务执行手册索引

> 本目录是 `docs/13-MVP实施契约与任务卡.md`(契约层)的「操作手册层」。
>
> 文档权威顺序链:**`01..10 → 13 §5 → agent-tasks/T00 → agent-tasks/Tnn`**。
>
> 12 §2 与 13 §1 一并扩展为:`代理开始任务前先读 01..10 摘要 → 13 §5 本卡 → 本目录 T00 → 本目录 Tnn`。

---

## 1. 使用顺序

1. 先读 `docs/01-项目开发总计划.md` 到 `docs/10-编码规范与协作流程.md`(了解范围/契约/协作门禁)
2. 再读 `docs/13-MVP实施契约与任务卡.md` 第 5 节本任务卡的「目标/来源/允许范围/禁止/完成定义」
3. 然后读本目录的 [T00-通用执行前置条件.md](./T00-通用执行前置条件.md)
4. 最后读本卡对应的 [Tnn-<名称>.md](./Tnn-<名称>.md),逐节执行

任何一份 Tnn 中标注「来源」必须能在 `02..05` 中找到对应章节;若 Tnn 与上游文档冲突,**立即停止并报告**(`12 §7` 标准报告格式)。

---

## 2. 文件清单

| 文件 | 作用 | 何时读 |
| --- | --- | --- |
| [README.md](./README.md) | 索引(本文) | 每次进入目录 |
| [T00-通用执行前置条件.md](./T00-通用执行前置条件.md) | 通用约定、禁止越界清单、完成报告模板 | 每次领取任务前 |
| [TEMPLATE.md](./TEMPLATE.md) | Tnn 文档结构模板(12 节固定顺序) | 起草新 Tnn 时 |
| [T01-基础数据库与迁移.md](./T01-基础数据库与迁移.md) | T01 step-by-step | 领取 T01 时 |
| [T02-认证会话.md](./T02-认证会话.md) | T02 step-by-step | 领取 T02 时 |
| [T03-简历与版本.md](./T03-简历与版本.md) | T03 step-by-step | 领取 T03 时 |
| [T04-职业资料.md](./T04-职业资料.md) | T04 step-by-step | 领取 T04 时 |
| [T05-JD管理.md](./T05-JD管理.md) | T05 step-by-step | 领取 T05 时 |
| [T06-AI同意与任务框架.md](./T06-AI同意与任务框架.md) | T06 step-by-step | 领取 T06 时 |
| [T07-岗位定制生成.md](./T07-岗位定制生成.md) | T07 step-by-step | 领取 T07 时 |
| [T08-来源确认与版本落地.md](./T08-来源确认与版本落地.md) | T08 step-by-step | 领取 T08 时 |
| [T09-JD规则覆盖度.md](./T09-JD规则覆盖度.md) | T09 step-by-step | 领取 T09 时 |
| [T10-私有PDF导出.md](./T10-私有PDF导出.md) | T10 step-by-step | 领取 T10 时 |
| [T11-MVP端到端验收.md](./T11-MVP端到端验收.md) | T11 step-by-step | 领取 T11 时 |

---

## 3. 与 `13 §4` 任务依赖图的对应

```text
T01 基础数据库与迁移
 ├─ T02 认证会话
 ├─ T03 简历与版本
 ├─ T04 职业资料
 └─ T05 JD 管理
T02 + T03 + T04 + T05
 └─ T06 AI 同意与任务框架
     └─ T07 岗位定制生成
         └─ T08 来源确认与版本落地
T03 + T05
 └─ T09 JD 规则覆盖度
T03
 └─ T10 私有 PDF 导出
T02-T10
 └─ T11 MVP 端到端验收
```

代理一次只领取一张卡,严格按依赖顺序推进。

---

## 4. 操作手册(Tnn)的统一 12 节结构

每份 Tnn 文档**至少**包含以下 12 节,顺序固定(由 `TEMPLATE.md` 固化):

1. **任务卡摘要**:复述 `13 §5` 本卡的「目标/来源/允许范围/禁止/完成定义」
2. **本卡依赖**:前置 Tnn + 外部依赖(MySQL 已启动等)
3. **目标文件清单**:精确到 `<路径>/<Class>.<ext>` 的新增/修改清单
4. **包结构与命名**:ASCII 文件树,遵循 `10 §5.1` 推荐分层
5. **配置项**:本卡要补/改的 `application.yml`、`pom.xml`、`vite.config.ts` 等字段(只列差异)
6. **数据库变更**(如有):Flyway 路径、命名规范、与 `04` 对应
7. **关键代码骨架**:实体/枚举、Repository、Service、Controller、DTO 字段表
8. **前端变更**(如有):Pinia store、api 模块、视图组件、路由
9. **测试清单**:`@DisplayName` 中文名清单,与 `13 §5` 必测场景一一对应
10. **验证命令**:从 `13` 复述 + 本卡特定命令
11. **停止条件**:本卡特有的 BLOCKED 触发条件
12. **完成报告**:从 `12 §8` + `13 §7` 模板化

---

## 5. 与既有约定的关系

本目录的 Tnn **不得**要求替换或重写以下既有约定(直接复用):

- 后端包根 `com.intelligentresume`、Java 17、Spring Boot 3.3.2
- `com.intelligentresume.common.api.ApiResponse` (`code/message/data/traceId`)
- `com.intelligentresume.common.error.ErrorCode`(已含 40001/40101/40301/40401/40901/42901/50001/50002/50003)
- `com.intelligentresume.common.error.GlobalExceptionHandler` + `BusinessException`
- `com.intelligentresume.common.api.TraceIdFilter`(写 `X-Trace-Id`)
- `com.intelligentresume.config.CorsConfig`(允许 Header 含 `Idempotency-Key`、`X-Trace-Id`)
- 系统健康接口 `GET /api/system/health`(已存在)
- 前端 `web/src/api/client.ts`(统一 axios + bearer token)
- 前端 `web/src/stores/auth.ts`(`useAuthStore`,只放 `accessToken`)
- 前端 `web/src/router/index.ts`(已有 `home/login/register/not-found`)
- PDF 服务 `pdf-service/src/server.js`(已有 `/health` + 错误格式)
- `.env.example`、`docker-compose.yml`、`.editorconfig`、`.gitignore`

---

## 6. 反馈与维护

- Tnn 与上游文档冲突时:**停止编辑**,按 `T00 §6` 的标准 BLOCKED 报告格式上报
- Tnn 中某条配置不再适用时:**先修改 02/04/05/13 上游文档**,再修改本目录 Tnn
- 新增 T12+ 任务卡时:复制 [TEMPLATE.md](./TEMPLATE.md),按 12 节填充,提交评审

---

## 7. 完成报告(强制)

代理每完成一张 Tnn,必须输出 `T00 §5` 中规定的完成报告模板,**缺任何一项不得报告 DONE**。