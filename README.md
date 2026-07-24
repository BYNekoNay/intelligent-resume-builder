# 智历

面向求职者的岗位定制简历平台。当前仓库已初始化为模块化单体骨架，MVP 主流程为：

`职业资料库 -> JD -> 岗位定制简历 -> 基础规则评分 -> 单模板私有 PDF`

详细范围、数据模型、接口和交付规则请从 [docs/README.md](docs/README.md) 开始阅读。

## 工程结构

```text
server/       Spring Boot API（Java 17）
web/          Vue 3 Web 应用（TypeScript）
pdf-service/  Node.js PDF 服务边界
docs/         产品、设计、测试和 AI 代理开发规范
```

## 本地启动

前置条件：Java 17、Maven 3.9+、Node.js 20+ 与本机 MySQL。Docker 不是本地验收的前置条件。

```powershell
# 1. 在忽略的根目录 .env 中配置本机 MySQL 连接（不要写入百炼密钥）
# SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/intelligent_resume?...
# SPRING_DATASOURCE_USERNAME=你的本机用户名
# SPRING_DATASOURCE_PASSWORD=你的本机密码

# 2. 启动由脚本管理的本机 MySQL + 百炼验收拓扑
.\scripts\Start-LocalValidation.ps1

# 3. 运行完整 API 验收（包含 AI 与 PDF 故障恢复）
.\scripts\Test-LocalFullFlow.ps1 -VerifyPdfRecovery

# 4. 停止脚本管理的本地服务
.\scripts\Stop-LocalValidation.ps1
```

完整本机 MySQL 配置、真实百炼门禁和浏览器 smoke 操作见 [docs/LOCAL_VALIDATION.md](docs/LOCAL_VALIDATION.md)。

初始健康检查：

- API：`GET http://localhost:8080/api/system/health`
- PDF 服务：`GET http://localhost:3001/health`

## 验证命令

```powershell
Set-Location server; mvn test
Set-Location web; npm run build
Set-Location pdf-service; npm run check
```

## 实现顺序

所有实现必须遵循 [docs/12-面向AI代理的开发流程与交付规范.md](docs/12-面向AI代理的开发流程与交付规范.md)。首批任务按认证、简历与版本、职业资料、JD、岗位定制生成、评分与私有 PDF 的顺序推进。
