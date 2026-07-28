# 智历

智历是面向求职者的岗位定制简历平台。用户维护一份可复用的个人档案和职业资料库，选择或粘贴 JD 后，系统先用真实百炼 AI 进行资料选材，用户确认后再生成可编辑、可追溯的结构化岗位简历。

生成过程不会直接用 AI 覆盖用户资料：岗位简历保留独立版本，草稿中的内容可追溯到已确认的资料；用户可以审核、编辑、归档和恢复版本。

## 核心能力

- **个人档案与职业目标**：维护联系方式、个人简介、目标职位、职级、行业和工作方式偏好。
- **可复用职业资料库**：工作、项目、教育、技能、证书等基础资料，以及量化成果、管理/协作经历和单技能证据卡。
- **JD 智能选材**：按资料偏好和 JD 规则预筛，AI 给出推荐理由、未采用资料和未覆盖要求，用户确认后才进入生成。
- **结构化岗位简历**：仅使用确认快照生成标准简历字段；联系方式由服务端合并，量化成果按展示口径脱敏。
- **审核、版本与导出**：草稿逐项审核，岗位简历独立保存；支持版本归档/恢复、规则覆盖度分析和私有 PDF 导出。
- **求职闭环**：提供 ATS 规则检查、投递状态管理、事实约束的沟通草稿、简历导入、有限轮次模拟面试和面试答案资产；所有用户资源均执行归属校验。

## 工程结构

```text
server/       Spring Boot API（Java 17、Flyway、MySQL、AI 任务工作器）
web/          Vue 3 + TypeScript Web 应用
pdf-service/  Node.js + Puppeteer 私有 PDF 渲染服务
scripts/      本地真实 AI、PDF 故障恢复和全流程验证脚本
docs/         产品、架构、接口、测试与运维文档
```

## 本地启动

本地验收不依赖 Docker。前置条件为 Java 17、Maven 3.9+、Node.js 20+ 和本地 MySQL。

1. 将根目录 [`.env.example`](.env.example) 复制为忽略的 `.env`，填写本地 MySQL 连接信息。
2. 在忽略的 `.env.live-ai` 中配置 `BAILIAN_API_KEY`。真实 AI 验收脚本会强制使用百炼，不会回退到替代模型。
3. 为 PDF 服务创建 `pdf-service/.env`，并确保其中的 `PDF_SERVICE_TOKEN` 与根 `.env` 一致。
4. 启动本地验证拓扑：

```powershell
.\scripts\Start-LocalValidation.ps1
```

服务地址：

- Web：`http://127.0.0.1:5173`
- API 健康检查：`http://127.0.0.1:8080/api/system/health`
- PDF 服务健康检查：`http://127.0.0.1:3001/health`

停止由脚本启动的本地服务：

```powershell
.\scripts\Stop-LocalValidation.ps1
```

完整 MySQL 配置、浏览器验收和故障恢复步骤见 [本地验证指南](docs/LOCAL_VALIDATION.md)。

## 配置与安全

以下敏感信息只能存放在忽略的 `.env`、`.env.live-ai`、服务密钥管理系统或部署环境变量中，禁止提交到仓库：

- `BAILIAN_API_KEY`
- `JWT_SECRET`
- `SPRING_DATASOURCE_PASSWORD`、`MYSQL_PASSWORD`、`MYSQL_ROOT_PASSWORD`
- `PDF_SERVICE_TOKEN`

真实 AI 使用 `AI_PROVIDER=bailian` 与 `BAILIAN_API_KEY`。`.env.live-ai` 仅用于本地真实调用验证；生产环境应通过部署环境变量或密钥管理系统提供同类配置。不要依赖任何开发默认 JWT 密钥或数据库密码。

## 验证命令

```powershell
Set-Location server
mvn test

Set-Location ..\web
npm run build
npx playwright test

Set-Location ..\pdf-service
npm run check
npm test
```

后端集成测试使用 H2/MySQL 兼容模式执行全部 Flyway 迁移，并覆盖 ATS、投递、沟通、导入、面试及答案资产的真实控制器契约。常规 Web E2E 使用可控路由数据；需要验证三个服务真实联调时，使用下方本地全流程脚本。

真实百炼端到端验证会创建临时账号和测试数据，并在结束时清理：

```powershell
.\scripts\Test-LocalFullFlow.ps1
```

如需同时验证 PDF 服务故障后的重试恢复：

```powershell
.\scripts\Test-LocalFullFlow.ps1 -VerifyPdfRecovery
```

## 云端部署

普通提交和 Pull Request 由 GitHub Actions 分别验证后端测试、Web 构建与浏览器 E2E、PDF 服务检查；ACR 镜像发布保持手动触发。配置、检查名称和分支保护建议见 [GitHub CI/CD 流水线](docs/CI.md)。

当前可复现发布链路为 GitHub Actions 构建 Linux/amd64 镜像、阿里云 ACR 保存成品镜像、ECS 仅拉取运行。Edge Nginx 是唯一公网入口，Web、API、PDF 和 MySQL 保持在容器私有网络中。

无域名测试环境可以使用公网 IP 与自签名 HTTPS；正式环境仍应配置可信域名证书、备份策略与告警接收渠道。完整操作、回滚、故障排查和注销 ECS 前备份要求见[部署运行手册](docs/DEPLOYMENT.md)。

## 文档入口

- [项目上下文（后续 AI / 新开发者先读）](PROJECT_CONTEXT.md)
- [文档索引](docs/README.md)
- [部署运行手册](docs/DEPLOYMENT.md)
- [本地验证指南](docs/LOCAL_VALIDATION.md)
- [系统架构设计](docs/03-系统架构设计说明书.md)
- [接口设计](docs/05-接口设计说明书.md)
- [测试计划与验收](docs/07-测试计划与验收说明书.md)
- [部署与运维](docs/08-部署与运维说明书.md)
