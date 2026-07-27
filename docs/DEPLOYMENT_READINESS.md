# 部署状态说明

当前可执行部署流程、故障排查、回滚和 ECS 注销前检查已统一到[部署运行手册](./DEPLOYMENT.md)。本文件保留运行边界与架构背景，不再作为发布步骤来源。

本文件是当前版本的运行事实来源。`01` 至 `13` 与 `docs/agent-tasks/` 中的 MVP 文档保留为历史设计、任务执行记录，不应覆盖本文件、根目录 `README.md` 或实际代码的现行行为。

## 当前交付

- 业务闭环：个人档案与职业目标、资料库、JD 智能选材、人工确认、结构化岗位简历、版本归档/恢复、PDF、投递和面试资产。
- AI：使用真实百炼调用；选材和生成是两阶段异步任务，生成内容保留确认资料来源。
- 观测：API 在内部暴露 `/actuator/prometheus`；AI/PDF 成功率、时延、失败分类、队列积压和 AI 配额均有 Prometheus 指标、Grafana 看板和告警规则。
- 可靠性：AI 与 PDF 任务均采用数据库租约领取，应用重启或节点退出后，过期租约可由其他实例重新领取。

## 生产拓扑

`deploy/docker-compose.prod.yml` 定义单机生产拓扑：

```text
Internet -> edge Nginx -> web Nginx -> Spring Boot API -> MySQL / PDF service
                                             |
                                             +-> Prometheus -> Grafana (optional internal profile)
```

- 只有 `edge` 映射宿主机 `80`；MySQL、API、PDF、Prometheus 与 Grafana 均在内部网络。
- TLS、443 监听和证书续期由阿里云部署时的公网 Nginx/证书方案负责；本仓库不伪造未部署的 TLS 能力。
- 监控默认不启动。需要时使用 `--profile monitoring`，并通过 SSH 隧道或内网入口访问 Grafana；不得将其直接暴露到公网。

## 部署前准备

1. 将 `deploy/production.env.example` 复制为仓库外或被 Git 忽略的 `deploy/production.env`。
2. 用密码管理器生成并填入 `JWT_SECRET`、`PDF_SERVICE_TOKEN`、数据库密码和 Grafana 管理密码。JWT/PDF Token 至少 32 个字符，数据库密码至少 16 个字符。
3. 填写真实的 `BAILIAN_API_KEY`、`PUBLIC_HOST`、CORS 来源和数据库地址。不要提交任何 `.env` 文件。
4. 以 `prod` profile 启动 API。该 profile 会拒绝开发默认 JWT、PDF Token、数据库密码，以及不安全的刷新 Cookie。
5. 先执行静态验证：

```powershell
Set-Location deploy
docker compose --env-file production.env.example -f docker-compose.prod.yml config --quiet
```

该命令只解析 Compose，不会拉取镜像或启动容器。

## 发布门禁

本地可运行以下命令；它不会启动 Docker，也不会执行真实百炼调用：

```powershell
.\scripts\Test-ReleaseReadiness.ps1
```

Gitee CI 定义在 `.gitee/workflows/ci.yml`，包含：后端测试和 Flyway 迁移验证、前端构建及 Playwright 回归、PDF 服务测试、生产 Compose 静态校验和 Git 空白检查。浏览器仅在 CI 临时环境安装，不要求开发机下载 Chromium。

真实百炼全流程仍是凭据环境下的显式验收：

```powershell
.\scripts\Test-LocalFullFlow.ps1
```

## 运维检查

- API readiness：`/actuator/health/readiness`（仅内部网络）
- 指标：`/actuator/prometheus`（仅内部网络）
- PDF 健康检查：`http://pdf-service:3001/health`（仅内部网络）
- 告警规则和 Grafana Provisioning：`monitoring/`

发布前应确认：Flyway 已成功执行、API readiness 为 `UP`、没有 AI/PDF 积压告警、数据库已完成加密备份并验证最近一次恢复演练。生产环境必须配置备份保留、RPO/RTO 和告警通知渠道；通知接收人尚未在仓库内硬编码。

## 服务器准入检查

在提供 SSH 连接信息后，先将 `scripts/Inspect-ServerReadiness.sh` 复制到服务器并执行：

```bash
bash Inspect-ServerReadiness.sh
```

## 无域名测试服务器

仅用于临时验收的公网 IP 服务器可使用 `deploy/docker-compose.ip-test.yml` 叠加配置和 `deploy/production.ip-test.env.example` 作为环境变量模板。该模式会：

- 在 443 上使用带 IP SAN 的自签名证书，并将 80 重定向到 HTTPS；浏览器首次访问会提示证书不受信任。
- 保持 `COOKIE_SECURE=true`，使刷新登录流程仍在 HTTPS 下验证。
- 将 MySQL、API、PDF 和 Nginx 限制在适合约 3.5 GiB 内存测试机的预算内；Prometheus/Grafana 不默认启动。

它不适合公开使用或正式投递。生产部署必须改回可信域名证书、正式容量规划和完整备份/告警策略。

该脚本只读取系统状态，不会安装软件、拉取镜像、启动容器或打印密钥。默认要求根分区至少保留 20 GiB、内存至少 4 GiB，并检查 Docker、Compose、80/443 端口、当前用户的 Docker 权限及可用的防火墙状态。阈值不足时应先扩容或确认资源分配，再开始部署。

还需由服务器所有者确认：域名已解析到服务器、TLS 证书方案、云安全组对 80/443 的放行、数据库备份目标与保留周期，以及告警通知接收人。以上条件未确认前，不执行镜像拉取、容器启动或数据迁移。
