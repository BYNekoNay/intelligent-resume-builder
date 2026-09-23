# 直接上传部署手册（非 Docker）

> 适用场景：单台 ECS 上以**源码上传 + 服务器原生进程**方式运行智历，不使用 Docker。
> 当前落地的测试环境：`8.160.165.227`（阿里云 ECS · Ubuntu 22.04 · 4 vCPU / 7.5 GiB）
> 与本文并列的 [部署运行手册](./DEPLOYMENT.md) 描述的是 Docker + ACR 镜像路线，两条路线互不影响。

---

## 1. 拓扑与端口

```text
                        ┌──────────────────────────────────────┐
 公网 :80  ────────────►│ nginx                                │
                        │  /      → /opt/.../app/web（静态）    │
                        │  /api/  → 127.0.0.1:8080             │
                        └──────────────────────────────────────┘
                                        │
                    ┌───────────────────┴──────────────────┐
                    ▼                                      ▼
        ┌───────────────────────┐            ┌─────────────────────────┐
        │ intelligent-resume-api│  HTTP+Token│ intelligent-resume-pdf  │
        │ Spring Boot :8080     │───────────►│ Node + Puppeteer :3001  │
        └───────────┬───────────┘            └───────────┬─────────────┘
                    │ JDBC                               │ Chromium
                    ▼                                    ▼
        ┌───────────────────────┐            ┌─────────────────────────┐
        │ MySQL 8.0 :3306       │            │ 本机 Chrome /           │
        │ 仅 127.0.0.1          │            │ chrome-headless-shell   │
        └───────────────────────┘            └─────────────────────────┘
```

| 服务 | 端口 | 绑定 | 公网 |
| --- | --- | --- | --- |
| nginx | 80 | `0.0.0.0` | 开放（安全组需放行 TCP 80） |
| API | 8080 | `127.0.0.1`（由 `SERVER_ADDRESS` 控制） | 关闭 |
| pdf-service | 3001 | `0.0.0.0`（应用未提供绑定参数，见 §8 残留风险） | 关闭 |
| MySQL | 3306 | `127.0.0.1` | 关闭 |

---

## 2. 服务器目录约定

```text
/opt/intelligent-resume/
├── src/                       上传的源码（构建用，保留以支持增量重建）
├── app/
│   ├── api/
│   │   ├── intelligent-resume-server-0.1.0-SNAPSHOT.jar
│   │   ├── .env               后端配置（600；后端从 CWD 读取）
│   │   └── pdf-output/        PDF 导出落盘目录
│   ├── web/                   nginx 静态根（保留 web.bak 供回滚）
│   └── pdf-service/           源码 + node_modules + .env（600）
├── logs/
├── backups/
├── secrets.env                JWT_SECRET / PDF_SERVICE_TOKEN / MYSQL_PASSWORD（600）
├── live-ai.env                BAILIAN_API_KEY / BAILIAN_MODEL（600）
├── deploy-direct.remote.sh    服务器侧部署执行器
└── *.service / host.conf     部署资产副本（安装到 /etc 用）
```

## 3. 使用的 systemd 单元与 nginx 站点

| 单元 | 源文件 | 安装位置 |
| --- | --- | --- |
| API | `deploy/systemd/intelligent-resume-api.service` | `/etc/systemd/system/` |
| PDF | `deploy/systemd/intelligent-resume-pdf.service` | `/etc/systemd/system/` |
| nginx 站点 | `deploy/nginx/host.conf` | `/etc/nginx/sites-available/intelligent-resume` |

两个单元都 `enable` 了开机自启，`Restart=on-failure`，日志走 journald（自带轮转）。

---

## 4. 首次部署（已实测通过）

### 4.1 服务器基础环境

```bash
timedatectl set-timezone Asia/Shanghai

# 2 GiB swap（本规格机器无 swap，OOM 会直接杀进程）
fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab

apt-get update
apt-get install -y openjdk-17-jdk-headless maven nginx mysql-server unzip ca-certificates fonts-noto-cjk \
  libnss3 libatk1.0-0 libatk-bridge2.0-0 libcups2 libdrm2 libxkbcommon0 libxcomposite1 \
  libxdamage1 libxfixes3 libxrandr2 libgbm1 libasound2 libpango-1.0-0 libcairo2 libxshmfence1
```

> `fonts-noto-cjk` 是**必需项**：缺失时 PDF 的中文会渲染成方块字。
> 后 14 个 lib 是 Chromium 运行库，任缺一个都会让 `/render` 返回 500。

### 4.2 MySQL 初始化

```bash
cat > /etc/mysql/mysql.conf.d/zz-resume.cnf <<'CNF'
[mysqld]
bind-address = 127.0.0.1
innodb_buffer_pool_size = 512M
performance_schema = OFF
max_connections = 100
character-set-server = utf8mb4
collation-server = utf8mb4_unicode_ci
CNF
systemctl restart mysql

mysql -uroot --protocol=socket <<SQL
CREATE DATABASE IF NOT EXISTS intelligent_resume
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'resume_app'@'127.0.0.1' IDENTIFIED WITH mysql_native_password BY '<随机口令>';
GRANT ALL PRIVILEGES ON intelligent_resume.* TO 'resume_app'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL
```

**不要手工建表**：表结构由应用启动时的 Flyway 前向迁移创建（当前 `V1` → `V25`，共 25 个迁移 / 24 张业务表）。

### 4.3 生成密钥

```bash
SEC=/opt/intelligent-resume/secrets.env
umask 077
{
  echo "JWT_SECRET=$(openssl rand -hex 48)"
  echo "PDF_SERVICE_TOKEN=$(openssl rand -hex 32)"
  echo "MYSQL_PASSWORD=$(openssl rand -hex 20)"
} > "$SEC"
chmod 600 "$SEC"
```

同时把百炼凭据放到 `/opt/intelligent-resume/live-ai.env`（600），内容为 `BAILIAN_API_KEY=...` 与 `BAILIAN_MODEL=...`。

> 一律使用 `openssl rand -hex`：十六进制不含 `=`、`+`、`/`，避免 `.env` 以 properties 解析时出现歧义。

### 4.4 写入运行配置

`/opt/intelligent-resume/app/api/.env`（600）：

```dotenv
SPRING_PROFILES_ACTIVE=default
SERVER_PORT=8080
SERVER_ADDRESS=127.0.0.1
SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/intelligent_resume?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
SPRING_DATASOURCE_USERNAME=resume_app
SPRING_DATASOURCE_PASSWORD=<secrets.env 的 MYSQL_PASSWORD>
JWT_SECRET=<secrets.env 的 JWT_SECRET>
COOKIE_SECURE=false
CORS_ALLOWED_ORIGINS=http://8.160.165.227
PDF_SERVICE_BASE_URL=http://127.0.0.1:3001
PDF_SERVICE_TOKEN=<secrets.env 的 PDF_SERVICE_TOKEN>
PDF_OUTPUT_DIR=/opt/intelligent-resume/app/api/pdf-output
BAILIAN_API_KEY=<live-ai.env>
BAILIAN_MODEL=qwen3.8-max
BAILIAN_MODEL_CHAIN=qwen3.8-max,glm-5.3,qwen3.8-27b,qwen3.8-2.4t-a95b,qwen3.8-max-0902,deepseek-v4.1-flash,deepseek-v4-pro-0813,kimi-k3
BAILIAN_READ_TIMEOUT_S=300
AI_CHAIN_QUOTA_COOLDOWN_S=1800
AI_CHAIN_TRANSIENT_COOLDOWN_S=60
AI_CHAIN_TOTAL_BUDGET_S=600
```

> **链总预算（`AI_CHAIN_TOTAL_BUDGET_S`）**：单个模型的读超时（默认 300s）乘以链长度会放大成数十分钟，
> 而 worker 处理单条任务期间会一直占住线程，后续 AI 任务会排队阻塞。超出总预算即停止顺延并快速失败，
> 由 worker 的重试机制稍后再跑。取值应大于单次读超时、小于可接受的最坏排队时长。

> **模型链（`BAILIAN_MODEL_CHAIN`）**：百炼的免费额度是**按模型**计量的，单模型额度耗尽会让全部 AI 功能一起失效。
> 这里配置有序模型链，按序尝试、失败顺延；额度耗尽或模型下线的条目进入冷却期被跳过。
> `BAILIAN_MODEL` 是链为空时的单模型回退项，建议与链首保持一致。
>
> ⚠ **不要复用历史已废弃的 `BAILIAN_MODELS` 变量名** —— 部署机上可能仍残留该僵尸变量（指向一批已耗尽的旧模型），
> 复用会被静默激活成模型链。详见 [模型链设计方案](./plans/2026-09-23-002-bailian-model-chain.md)。

`/opt/intelligent-resume/app/pdf-service/.env`（600）：

```dotenv
NODE_ENV=production
PDF_SERVICE_PORT=3001
PDF_SERVICE_TOKEN=<与 api 一致>
```

> **为什么不用 `prod` profile**：`ProductionConfigurationValidator` 带 `@Profile("prod")`，只要 prod 生效就强制
> `COOKIE_SECURE=true`；而带 `Secure` 属性的 Cookie 在明文 HTTP 下不会被浏览器保存，两者互斥，应用会直接拒绝启动。
> 明文测试环境因此使用默认 profile——actuator 暴露与 health 探针本就配置在 `application.yml` 基础段。
> 若切换 HTTPS，请改回 `prod` 并把 `COOKIE_SECURE=true`，即可重新获得该校验器的生产级防护。

### 4.5 安装服务与站点

```bash
install -m 644 deploy/systemd/intelligent-resume-api.service /etc/systemd/system/
install -m 644 deploy/systemd/intelligent-resume-pdf.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now mysql nginx intelligent-resume-pdf intelligent-resume-api

install -m 644 deploy/nginx/host.conf /etc/nginx/sites-available/intelligent-resume
ln -sf /etc/nginx/sites-available/intelligent-resume /etc/nginx/sites-enabled/intelligent-resume
rm -f /etc/nginx/sites-enabled/default
nginx -t && systemctl reload nginx
```

---

## 5. 日常迭代部署（一条命令）

```bash
bash scripts/deploy-direct.sh
```

脚本做的事：本机打包源码（排除 `node_modules` / `target` / `dist` / `.env`，并做泄露校验）→ 上传 →
解压 → 服务器端 `mvn clean package` + `npm ci && npm run build` + `npm ci`（PDF）→ 原子替换产物 →
重启服务 → 等待 readiness → 输出健康检查。

可用环境变量覆盖：`SERVER`、`SSH_USER`、`SSH_KEY`、`REMOTE_ROOT`。

> 服务器侧逻辑在 `scripts/deploy-direct.remote.sh`，可单独在服务器上执行：
> `bash /opt/intelligent-resume/deploy-direct.remote.sh`

---

## 6. 验收结果（2026-09-23 实测）

| 编号 | 检查项 | 结果 |
| --- | --- | --- |
| AC-01 | API readiness | ✅ `{"status":"UP"}`，启动耗时 26.1s |
| AC-02 | 业务健康契约 | ✅ `status: UP`，`api` / `ai-provider` / `pdf-renderer` 三项均 `UP` |
| AC-03 | DB 迁移 | ✅ Flyway 25 个迁移全部 applied，最高版本 `V25`，24 张表 |
| AC-04 | PDF 服务 | ✅ `/health` 返回 `pdf-renderer: UP` |
| AC-05 | 静态站 | ✅ `GET /` → 200，SPA HTML + 带哈希资源 200 |
| AC-06 | 反代 | ✅ `GET /api/system/health`（经 nginx）→ 200 |
| AC-07 | 公网可达 | ✅ `http://8.160.165.227/` → 200 |
| AC-08 | 注册登录闭环 | ✅ 公网注册 → 200 + JWT；`/api/auth/me` 返回本人；未认证 401 |
| AC-09 | 资料 CRUD | ✅ 创建职业资料 → 200，`evidenceReady: true`；列表返回一致 |
| AC-10 | 中文 PDF | ✅ 7 个模板全部渲染成功；PDF 内嵌 `NotoSansCJKsc-Regular/Bold` 子集，证实中文非方块字 |
| AC-11 | AI 闭环 | ❌ **被账号额度阻塞**，见 §7 |
| AC-12 | 重启韧性 | ✅ 完整部署脚本跑通并重启两个服务后，全部健康检查仍通过 |
| AC-13 | 安全边界 | ✅ 公网 8080/3001/3306 全封闭、80 开放；CORS 合法 Origin 放行 / 非法 Origin 403；`.env` 权限 600 |

---

## 7. 当前阻塞项：百炼免费额度已耗尽

2026-09-23 直接调用百炼兼容接口验证凭据：

```text
HTTP 403  (0.14s)
{"error":{"code":"AllocationQuota.FreeTierOnly",
          "message":"Free quota exhausted. To continue accessing the model on a paid basis,
                     please add funds or disable the \"use free tier only\" mode in the management console."}}
```

判定：

- 密钥**本身有效**（无效密钥会返回 401 `InvalidApiKey`，此处是被额度策略拒绝）。
- 账号处于「仅使用免费额度」模式且额度已用尽，因此**所有 AI 功能（JD 选材 / 岗位生成 / ATS 分析 / 沟通草稿 / 模拟面试 / 内联润色）在当前状态下都会失败**。
- 非代码或部署问题，需在阿里云百炼控制台**充值**或**关闭「仅用免费额度」模式**后即可恢复。

注意：`/api/system/health` 的 `ai-provider` 项只检查「是否注册了可用 provider」（`providerRegistry.hasAvailableProvider()`），
**不代表密钥可用**。验证 AI 是否真的可用必须发起一次真实调用。

---

## 8. 已知坑与残留风险

| 项 | 说明 | 处置 |
| --- | --- | --- |
| Node 路径 | 本机 Node 不在 `/usr/bin`（`/usr/bin/node` 与 `/usr/bin/npm` 均不存在），实际在 `/usr/local/bin/node`，再软链到 `/root/.hermes/node/bin/`。单元里写死绝对路径，否则 systemd 报 `status=203/EXEC` | 单元已改为直接调用 `/usr/local/bin/node --env-file=.env src/server.js`；更换 Node 安装位置时需同步修改 |
| Node 来源 | 该 Node 属于另一个项目的工具链（`.hermes`）。若该目录被清理，PDF 服务会启动失败 | 建议后续改为独立安装 Node 22 LTS；当前可用 |
| pdf-service 绑定 | 应用用 `app.listen(port)` 未指定 host，实际监听 `0.0.0.0:3001` | 目前依赖安全组封闭该端口；如需彻底收敛需改代码加 `server.address` 类参数 |
| 默认 profile 兜底值 | 默认 profile 保留了开发兜底值，若 `.env` 加载失败会静默回退 | 已通过运行期端到端验证补偿（CORS 生效 → 浏览器可跨源；PDF Token 生效 → 导出成功）；这两项任一失败会立即暴露 |
| Chromium 下载慢 | 首次 `npm ci` 需从 Google 源下载约 470 MiB，实测约 25 分钟 | 只发生一次；缓存位于 `/root/.cache/puppeteer`，重装前勿删 |
| 内存 | 7.5 GiB 无 swap 时会 OOM | 已加 2 GiB swap，并把 JVM 堆封顶 `-Xmx1024m`、MySQL buffer pool 限 512M；构建与运行不要同时进行 |
| 时区 | 容器/主机时区须为 `Asia/Shanghai`，与 JDBC `serverTimezone` 对齐 | 已设置 |

---

## 9. 回滚

| 场景 | 动作 |
| --- | --- |
| 新版 API 异常 | 用 `src/server/target/*.jar` 的上一版本覆盖 `app/api/` 下的 jar，`systemctl restart intelligent-resume-api` |
| 前端产物异常 | `rm -rf app/web && mv app/web.bak app/web`，`systemctl reload nginx` |
| PDF 服务异常 | `rm -rf app/pdf-service && mv app/pdf-service.bak app/pdf-service`，`systemctl restart intelligent-resume-pdf` |
| 数据库 | **不要使用 `flyway clean`**。用 `mysqldump --single-transaction` 备份恢复；迁移只前向执行 |

> 每次部署都会自动保留 `web.bak` 与 `pdf-service.bak`，因此回滚窗口为「最近一次成功部署之前」。

## 10. 日志与排查

```bash
journalctl -u intelligent-resume-api -f
journalctl -u intelligent-resume-pdf -f
journalctl -u nginx --since "10 min ago"
mysql -h127.0.0.1 -uresume_app -p -e "SELECT version,success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;" intelligent_resume
curl -s http://127.0.0.1:8080/api/system/health
curl -s http://127.0.0.1:3001/health
```

## 11. 安全提醒

以下文件只存在于服务器，权限 `600`，**永不入 Git、永不出现在日志与截图**：

`/opt/intelligent-resume/secrets.env`、`/opt/intelligent-resume/live-ai.env`、
`/opt/intelligent-resume/app/api/.env`、`/opt/intelligent-resume/app/pdf-service/.env`
