# 部署运行手册

本手册是当前唯一的云端发布与单机部署操作入口。

```text
GitHub Actions 构建 Linux/amd64 镜像
  -> 阿里云 ACR 私有仓库
  -> ECS 仅拉取镜像并以 Docker Compose 运行
```

ECS 不构建业务镜像，也不需要直接访问 Docker Hub。发布包含 API、Web、PDF、Edge 和镜像化的 MySQL 五个镜像。

本手册适用于无域名的公网 IP 测试环境。正式生产环境应使用可信域名证书、备份策略和告警接收渠道。

## 准备条件

- GitHub `master` 包含 `.github/workflows/ci.yml` 和 `.github/workflows/publish-acr-images.yml`，并已启用 Actions；发布镜像前目标提交的 CI 必须通过。
- 阿里云 ACR 已创建私有仓库：`intelligent-resume-api`、`intelligent-resume-web`、`intelligent-resume-pdf`、`intelligent-resume-edge`、`intelligent-resume-mysql`。
- ECS 与 ACR 位于同一区域，安装 Docker Engine、Docker Compose v2，至少保留 4 GiB 内存和 20 GiB 磁盘空间。
- 若 ECS 上保留仓库副本，可执行 `scripts/Inspect-ServerReadiness.sh` 做部署前检查；该脚本只读，不改变服务器。

在 GitHub 仓库 `Settings -> Secrets and variables -> Actions` 配置以下 Secrets：

| Secret | 用途 |
| --- | --- |
| `ACR_REGISTRY` | ACR Registry 域名 |
| `ACR_NAMESPACE` | ACR 命名空间 |
| `ACR_USERNAME` | 具备推送权限的 ACR 账号 |
| `ACR_PASSWORD` | 账号密码或访问令牌 |

不要将这些值写入 Git、Compose 文件或文档。ECS 建议使用独立的只读拉取账号。

## 构建并推送镜像

1. 将要发布的代码推送到 GitHub `master`。
2. 打开 `Actions -> Publish ACR Images -> Run workflow`。
3. 输入不可变标签，例如 `release-YYYYMMDD-N`；不要覆盖既有标签。
4. 等待 `publish` Job 成功。它调用 [Build-And-PushRegistryImages.sh](../scripts/Build-And-PushRegistryImages.sh) 构建并推送五个镜像。

如果 PDF 构建失败，确认 `pdf-service/Dockerfile` 仍包含 `unzip` 与 Chromium 运行库。若 ACR 登录或推送失败，只检查 Secret 名称、权限和命名空间，不要在日志中打印密钥。

## 配置 ECS

以下命令使用 `/opt/intelligent-resume/app` 作为发布目录，可按实际路径调整。

### 同步编排文件

将下列无密钥文件同步到 ECS 的 `deploy/` 目录：

- `deploy/docker-compose.prod.yml`
- `deploy/docker-compose.registry.yml`
- `deploy/docker-compose.ip-test.yml`
- `deploy/nginx/edge-ip-test.conf.template`

不要覆盖服务器上的 `production.ip-test.env`，该文件只保存在服务器并被 Git 忽略。

### 创建测试环境文件

复制 `deploy/production.ip-test.env.example` 为 `production.ip-test.env`，填写真实密钥与发布定位：

```dotenv
PUBLIC_HOST=<公网 IP>
DEPLOY_ENV_FILE=production.ip-test.env
IMAGE_REGISTRY=<ACR Registry>
IMAGE_NAMESPACE=<ACR 命名空间>
IMAGE_TAG=release-YYYYMMDD-N
TEST_TLS_DIR=/opt/intelligent-resume/tls
```

还必须设置 `MYSQL_DATABASE`、`MYSQL_USER`、`MYSQL_PASSWORD`、`MYSQL_ROOT_PASSWORD`、`JWT_SECRET`、`PDF_SERVICE_TOKEN`、`BAILIAN_API_KEY` 和 `GRAFANA_ADMIN_PASSWORD`。

关键约束：`SPRING_DATASOURCE_USERNAME` 必须等于 `MYSQL_USER`，`SPRING_DATASOURCE_PASSWORD` 必须等于 `MYSQL_PASSWORD`。MySQL 数据卷首次初始化后会固定应用用户密码；修改环境文件不会重置已有数据库用户密码。

创建自签名测试证书：

```bash
sudo install -d -m 700 /opt/intelligent-resume/tls
sudo openssl req -x509 -nodes -newkey rsa:2048 -days 30 \
  -keyout /opt/intelligent-resume/tls/tls.key \
  -out /opt/intelligent-resume/tls/tls.crt \
  -subj "/CN=<公网 IP>" \
  -addext "subjectAltName=IP:<公网 IP>"
sudo chmod 600 /opt/intelligent-resume/tls/tls.key
```

### 登录 ACR 与开放网络

在 ECS 以拉取账号登录私有 ACR，密码仅在交互提示中输入：

```bash
docker login <ACR Registry> --username <ACR 用户名>
```

在阿里云 ECS 安全组添加一条入方向规则：TCP `443`，来源 `0.0.0.0/0`。不要开放 MySQL `3306`、API `8080`、PDF `3001`、Prometheus 或 Grafana。公网 IP 测试环境的自签名证书会产生浏览器警告，这是预期行为。

## 部署与验证

```bash
cd /opt/intelligent-resume/app/deploy

docker compose --env-file production.ip-test.env \
  -f docker-compose.prod.yml \
  -f docker-compose.registry.yml \
  -f docker-compose.ip-test.yml pull

docker compose --env-file production.ip-test.env \
  -f docker-compose.prod.yml \
  -f docker-compose.registry.yml \
  -f docker-compose.ip-test.yml up -d
```

等待 API 健康后检查：

```bash
docker compose --env-file production.ip-test.env \
  -f docker-compose.prod.yml \
  -f docker-compose.registry.yml \
  -f docker-compose.ip-test.yml ps

docker compose --env-file production.ip-test.env \
  -f docker-compose.prod.yml \
  -f docker-compose.registry.yml \
  -f docker-compose.ip-test.yml \
  exec -T api curl -fsS http://127.0.0.1:8080/actuator/health/readiness

curl -k -I https://127.0.0.1/
curl -k -I https://<公网 IP>/
```

预期结果：MySQL、PDF、API 显示 `healthy`，HTTPS 返回 `200`。基础设施就绪后再进行真实百炼 AI 调用，避免无效消耗额度。

## 常见故障

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `pull access denied` | ECS Docker 未登录 ACR，或镜像定位错误 | 重新执行 `docker login`；核对 `IMAGE_REGISTRY`、`IMAGE_NAMESPACE` 与 `IMAGE_TAG` |
| `IMAGE_REGISTRY is required` | 环境文件缺少镜像定位变量 | 补齐 `IMAGE_REGISTRY`、`IMAGE_NAMESPACE`、`IMAGE_TAG` |
| API 提示 `Access denied for user` | API 数据源密码与 MySQL 用户密码不一致 | 令 `SPRING_DATASOURCE_PASSWORD` 与 `MYSQL_PASSWORD` 相同，重建 API；不要删除数据卷规避问题 |
| 服务器本机 `200`，公网超时 | 安全组或系统防火墙未放行 443 | 添加 TCP 443 入方向规则；确认 Edge 映射 443 |
| 浏览器证书告警 | IP 测试环境使用自签名证书 | 仅测试时手动继续；正式环境切换可信域名与证书 |

## 回滚

将 `production.ip-test.env` 中的 `IMAGE_TAG` 改为上一条成功发布的不可变标签，重新执行 `pull` 与 `up -d`，再验证 readiness 和 HTTPS。数据库迁移由 Flyway 前向执行；若新迁移不兼容旧应用，必须先使用已验证的数据库恢复方案。

## 注销 ECS 前

注销实例会永久丢失本地镜像、数据库卷、PDF 导出卷、证书和服务器环境文件。注销前必须：

先在 ECS 的 `deploy/` 目录导出数据。以下命令不打印密码，且必须在服务仍在运行时执行：

```bash
set -euo pipefail
BACKUP_DIR="/opt/intelligent-resume/backups/$(date +%F-%H%M%S)"
install -d -m 700 "$BACKUP_DIR"

COMPOSE=(docker compose --env-file production.ip-test.env \
  -f docker-compose.prod.yml \
  -f docker-compose.registry.yml \
  -f docker-compose.ip-test.yml)

# 导出全部应用数据。--single-transaction 尽量避免阻塞在线请求。
"${COMPOSE[@]}" exec -T mysql sh -c \
  'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --events "$MYSQL_DATABASE"' \
  > "$BACKUP_DIR/mysql.sql"

# PDF 导出目录挂载在 API 容器中；从运行中的容器复制即可取得该命名卷内容。
API_CONTAINER="$("${COMPOSE[@]}" ps -q api)"
test -n "$API_CONTAINER"
docker cp "$API_CONTAINER:/var/lib/intelligent-resume/pdf-output/." "$BACKUP_DIR/pdf-output"

# 证书私钥与环境文件包含敏感信息，只可临时保存在受控备份目录中。
sudo tar -C /opt/intelligent-resume -czf "$BACKUP_DIR/tls.tar.gz" tls
cp production.ip-test.env "$BACKUP_DIR/production.ip-test.env"
sha256sum "$BACKUP_DIR/mysql.sql" "$BACKUP_DIR/tls.tar.gz" > "$BACKUP_DIR/SHA256SUMS"
find "$BACKUP_DIR/pdf-output" -type f -print0 | sort -z | xargs -0 -r sha256sum >> "$BACKUP_DIR/SHA256SUMS"
```

将整个备份目录传到实例外部的受控且加密的存储后，再核对校验和。例如使用独立备份主机：

```bash
scp -r "$BACKUP_DIR" <backup-user>@<backup-host>:/secure-backups/intelligent-resume/
```

恢复演练必须在新的、隔离的 ECS 或测试环境进行，不能覆盖正在提供服务的数据库：先按“配置 ECS”准备 Compose 与环境文件，仅启动 `mysql`，再导入 SQL；随后复制 `pdf-output/` 和 TLS 文件，启动完整服务并执行本手册的 readiness 与 HTTPS 验证。

```bash
"${COMPOSE[@]}" up -d mysql
MYSQL_CONTAINER="$("${COMPOSE[@]}" ps -q mysql)"
docker cp "$BACKUP_DIR/mysql.sql" "$MYSQL_CONTAINER:/tmp/mysql.sql"
"${COMPOSE[@]}" exec -T mysql sh -c \
  'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" < /tmp/mysql.sql'
```

完成备份与恢复演练后，再执行以下清单：

1. 记录当前 `IMAGE_TAG`、ACR Registry、命名空间和 GitHub Actions Run 链接。
2. 在密码管理器保存部署环境变量，不要复制到仓库。
3. 确认没有待处理的 PDF/AI 任务或用户仍需下载的文件。
4. 可选执行 `docker compose ... down` 停止服务；在备份完成前绝对不要执行 `down -v`。

重新购置 ECS 后，从“配置 ECS”开始即可恢复运行环境；镜像仍保存在 ACR，代码和工作流仍保存在 GitHub。
