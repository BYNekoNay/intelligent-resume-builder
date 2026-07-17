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

前置条件：Java 17、Maven 3.9+、Node.js 20+、Docker Desktop（用于 MySQL）。

```powershell
# 1. 启动本地 MySQL
Copy-Item .env.example .env
docker compose up -d mysql

# 2. 启动后端 API（http://localhost:8080）
Set-Location server
mvn spring-boot:run

# 3. 新终端：启动前端（http://localhost:5173）
Set-Location web
Copy-Item .env.example .env.local
npm install
npm run dev

# 4. 新终端：启动 PDF 服务（http://localhost:3001）
Set-Location pdf-service
npm install
npm run dev
```

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
