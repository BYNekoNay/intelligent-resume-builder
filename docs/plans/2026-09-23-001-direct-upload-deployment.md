# 直接上传部署方案 · 8.160.165.227 测试环境

> ⚠ **本文已被取代（2026-09-25 标注）**
>
> 本文针对旧测试环境 `8.160.165.227`（阿里云 · Ubuntu 22.04）制定，该机**已到期下线**；
> 当前测试环境为 `101.35.239.218:8088`（腾讯云 · Ubuntu 26.04 · 与另一项目共用主机）。
>
> **因此文中以下内容已失效，不要照做**：目标主机与 IP、Ubuntu 22.04 的包名与依赖清单、
> `listen 80` 的 nginx 配置、`-Xmx1024m` / `innodb_buffer_pool_size=512M` 等旧内存参数、
> `/usr/local/bin/node`、`IDENTIFIED WITH mysql_native_password` 建用户语句、
> `CORS_ALLOWED_ORIGINS` 取值与"公网可达"的验收结论。
>
> **当前有效的操作依据**：`docs/DEPLOYMENT_DIRECT.md`（部署与验收清单）+ `docs/decisions/ADR-007`（环境迁移决策）。
> 本文保留用于追溯"首次直连部署"的方案设计与决策背景，属工程档案，**不再是操作手册**。
>
> 另：原文状态行的"待确认（未动工）"已过时 —— 该方案随后已实施并在旧环境验收通过。

> 生成日期：2026-09-23
> 目标主机：`8.160.165.227`（阿里云 ECS，Ubuntu 22.04.5 LTS）
> 状态：**待确认（未动工）** ← 该状态已过时，见上方取代声明
> 依据：本机实测 `pom.xml` / `package.json` / 服务器只读侦察结果，非文档推断

---

## 1. 方案定位与硬约束

| 项 | 决定 |
|---|---|
| 部署方式 | **源码上传 + 服务器原生进程（systemd）**，不使用 Docker |
| 不使用 Docker 的原因 | 宿主内存有限；现有 `docs/DEPLOYMENT.md` 的 ACR + Compose 路线依赖 5 个私有镜像仓库，当前无 ACR 命名空间，路线不可用 |
| 是否改业务代码 | **不改**。本次只新增部署资产（脚本 + systemd 单元 + nginx 配置 + 部署文档） |
| 数据库 | 服务器本机安装 MySQL 8.0，仅监听 `127.0.0.1` |
| 前端入口 | nginx 托管静态资源 + 反代 `/api` |
| 访问协议 | 默认 HTTP（80），无域名、无证书；如需 HTTPS 走自签名（见 §9 决策 D1） |

---

## 2. 目标环境实测基线

| 维度 | 实测值 | 判定 |
|---|---|---|
| OS / 内核 | Ubuntu 22.04.5 LTS / 5.15.0-190-generic | 可用 |
| CPU | 4 vCPU | 够用 |
| 内存 | 7522 MB 总 / **6855 MB 可用**，**无 swap** | 够用，但需限制 JVM 堆 |
| 磁盘 | `/` 40G，已用 7.6G，**可用 30G** | 够用 |
| Java | **未安装** | ✗ 需安装 openjdk-17 |
| Maven | **未安装** | ✗ 需安装 |
| Node.js | **v26.8.2** / npm 11.19.1 | 已装，npm 已可用 |
| Docker | 29.8.0 已装但**无任何容器** | 本次不使用 |
| nginx | **未安装** | ✗ 需安装（1.18.0 可装） |
| MySQL | **未安装** | ✗ 需安装（8.0.46 可装） |
| 监听端口 | **仅 22**（+ systemd-resolved:53） | 端口全空，无冲突 |
| apt 源 | `mirrors.cloud.aliyuncs.com`（阿里云内网源） | 安装速度快 |
| 外网连通 | Maven Central 200/1.5s · npm 200 · npmmirror 200 · dashscope 可达 · storage.googleapis.com 可达 | 服务器具备自建自构建条件 |
| 既有应用 | `/opt` 仅有 containerd；`/root` 有另一项目 cyberpersona 的备份包（无运行服务） | 路径无冲突 |

---

## 3. 部署拓扑与端口规划

```
                    ┌──────────────────────────────────────┐
 公网 :80  ────────►│ nginx (systemd)                      │
                    │  /            → /opt/.../app/web     │ 静态 dist
                    │  /api/        → 127.0.0.1:8080       │ 反代
                    └──────────────────────────────────────┘
                                    │
                  ┌─────────────────┴──────────────────┐
                  ▼                                    ▼
        ┌───────────────────┐               ┌────────────────────┐
        │ API (systemd)     │  HTTP 调用     │ pdf-service        │
        │ Spring Boot 3.3   │──────────────►│ (systemd) Node 26  │
        │ :8080 (localhost) │               │ :3001 (localhost)  │
        └─────────┬─────────┘               └────────┬───────────┘
                  │ JDBC                             │ Puppeteer
                  ▼                                  ▼
        ┌───────────────────┐               ┌────────────────────┐
        │ MySQL 8.0.46      │               │ 本机 Chromium      │
        │ :3306 (localhost) │               │ (puppeteer 自带)   │
        └───────────────────┘               └────────────────────┘
```

| 服务 | 端口 | 绑定 | 对外 |
|---|---|---|---|
| nginx | 80 | `0.0.0.0` | ✅ 需在安全组放行 |
| API | 8080 | `127.0.0.1` | ❌ |
| pdf-service | 3001 | `127.0.0.1` | ❌ |
| MySQL | 3306 | `127.0.0.1` | ❌ |

### 服务器目录规划

```
/opt/intelligent-resume/
├── src/                      上传的源码（构建用，保留以便增量重建）
├── app/
│   ├── api/                  intelligent-resume-server-0.1.0-SNAPSHOT.jar
│   │   ├── .env              后端配置（后端会从 CWD 读取 .env）
│   │   └── pdf-output/       PDF 导出目录
│   ├── web/                  nginx root = dist 内容
│   └── pdf-service/          node_modules + src + .env
├── logs/                     api.log / pdf.log（journald 之外的落盘副本）
└── backups/                  数据库备份
```

### 内存预算（总量 7.5G，无 swap → 必须封顶）

| 组件 | 上限 | 手段 |
|---|---|---|
| MySQL 8 | ~700 MB | `innodb_buffer_pool_size=512M`，`performance_schema=OFF` |
| API (JVM) | ~1.1 GB | `-Xms256m -Xmx1024m -XX:MaxMetaspaceSize=256m` |
| pdf-service + Chromium | ~700 MB | 单实例、串行渲染（与现有实现一致） |
| nginx | ~20 MB | — |
| **合计** | **≈ 2.5 GB** | 余量 ≈ 4 GB，安全 |

---

## 4. 执行步骤（Phase D1 ~ D7）

### D1 服务器基础环境（只装缺的）

```bash
apt-get update
apt-get install -y openjdk-17-jdk-headless maven nginx mysql-server
apt-get install -y unzip ca-certificates fonts-noto-cjk
# Chromium 运行库（Puppeteer 必需）
apt-get install -y libnss3 libatk1.0-0 libatk-bridge2.0-0 libcups2 libdrm2 \
  libxkbcommon0 libxcomposite1 libxdamage1 libxfixes3 libxrandr2 libgbm1 \
  libasound2 libpango-1.0-0 libcairo2 libxshmfence1
```

- `fonts-noto-cjk` **必须装**：否则 PDF 中文渲染会出方块字。
- 安装后核对 `java -version` 为 17.x。

### D2 MySQL 初始化（最小权限）

```bash
mysql -uroot <<'SQL'
CREATE DATABASE IF NOT EXISTS intelligent_resume
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'resume_app'@'127.0.0.1' IDENTIFIED BY '<随机16位>';
CREATE USER IF NOT EXISTS 'resume_app'@'localhost' IDENTIFIED BY '<随机16位>';
GRANT ALL PRIVILEGES ON intelligent_resume.* TO 'resume_app'@'127.0.0.1';
GRANT ALL PRIVILEGES ON intelligent_resume.* TO 'resume_app'@'localhost';
FLUSH PRIVILEGES;
SQL
```

- 表结构**不手工执行 SQL**：由应用启动时 Flyway 自动跑 `V1`→`V25`。
- 资源收紧写入 `/etc/mysql/mysql.conf.d/zz-resume.cnf`：
  `innodb_buffer_pool_size=512M` / `performance_schema=OFF` / `bind-address=127.0.0.1`。

### D3 上传源码并构建

本机打包（排除 `node_modules` / `target` / `dist` / `.git`）：

```bash
tar czf /tmp/resume-src.tar.gz \
  --exclude=node_modules --exclude=target --exclude=dist --exclude=.git \
  server web pdf-service
scp -i ~/.ssh/id_ed25519 /tmp/resume-src.tar.gz root@8.160.165.227:/opt/intelligent-resume/
```

服务器构建：

```bash
cd /opt/intelligent-resume/src
mvn -f server/pom.xml -DskipTests clean package      # 产出 target/*.jar
npm --prefix web ci && npm --prefix web run build    # 产出 web/dist
npm --prefix pdf-service ci                          # Puppeteer 自动下载 Linux Chromium
```

构建期峰值内存约 1.5–2 GB，当前余量 6.8 GB，安全。

### D4 生成配置与密钥

服务器上生成（**密钥不回传本机、不进 Git**）：

```bash
JWT_SECRET=$(openssl rand -base64 48)
PDF_SERVICE_TOKEN=$(openssl rand -base64 48)
MYSQL_PASSWORD=$(openssl rand -base64 24)
```

`/opt/intelligent-resume/app/api/.env` 关键项：

```dotenv
# 刻意使用默认 profile，不用 prod —— 见 §7「已实测确认的硬约束」
SPRING_PROFILES_ACTIVE=default
SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/intelligent_resume?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
SPRING_DATASOURCE_USERNAME=resume_app
SPRING_DATASOURCE_PASSWORD=<D4 生成>
JWT_SECRET=<D4 生成>
COOKIE_SECURE=false                 # HTTP 模式必须为 false，否则刷新 Cookie 不生效
CORS_ALLOWED_ORIGINS=http://8.160.165.227
PDF_SERVICE_BASE_URL=http://127.0.0.1:3001
PDF_SERVICE_TOKEN=<D4 生成，与 pdf-service 一致>
PDF_OUTPUT_DIR=/opt/intelligent-resume/app/api/pdf-output
BAILIAN_API_KEY=<见决策 D3>
BAILIAN_MODEL=qwen3.7-plus-2026-05-26
BAILIAN_READ_TIMEOUT_S=300
```

`/opt/intelligent-resume/app/pdf-service/.env`：
`NODE_ENV=production`、`PDF_SERVICE_PORT=3001`、`PDF_SERVICE_TOKEN`（与 api 一致）。
`chmod 600` 所有 `.env`。

### D5 systemd 托管（不用 nohup）

新增两个单元，实现崩溃自启 + 开机自启 + 日志归集：

- `intelligent-resume-api.service`
  `ExecStart=/usr/bin/java -Xms256m -Xmx1024m -XX:MaxMetaspaceSize=256m -jar <jar>`
  `WorkingDirectory=/opt/intelligent-resume/app/api`
  `Restart=on-failure` / `RestartSec=10`
- `intelligent-resume-pdf.service`
  `ExecStart=/usr/bin/npm start`（工作目录 `app/pdf-service`，由 `--env-file=.env` 注入 Token）
  `Restart=on-failure`

启动顺序：`mysql` → `pdf-service` → `api`（api 依赖 pdf 的 readiness）。

### D6 nginx 反代与静态托管

改写仓库里的 `deploy/nginx/web.conf`（原版反代到 Docker 服务名 `api:8080`，宿主机部署需改为回环地址）：

```nginx
server {
    listen 80 default_server;
    server_name _;
    root /opt/intelligent-resume/app/web;
    index index.html;
    client_max_body_size 5m;          # 与后端 multipart 上限对齐，避免简历导入 413

    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 300s;      # AI 任务轮询窗口对齐
    }

    location / {
        try_files $uri $uri/ /index.html;
        add_header Cache-Control "no-cache";
    }
}
```

前端 `client.ts` 的 `baseURL` 默认 `/`，同源反代下**无需注入 `VITE_API_BASE_URL`**。

### D7 安全组与首次验证

- **需你在阿里云控制台放行入方向 TCP 80**（我无权操作控制台）。
- 明确**不放行** 8080 / 3001 / 3306。

---

## 5. 验收标准（QA 门禁）

| 编号 | 检查项 | 通过标准 |
|---|---|---|
| AC-01 | API 就绪 | `curl http://127.0.0.1:8080/actuator/health/readiness` 返回 `UP` |
| AC-02 | 业务健康契约 | `GET /api/system/health` 返回 `checks` 且各子项可用 |
| AC-03 | DB 迁移 | 日志无 Flyway 报错，`flyway_schema_history` 最高版本 = `V25` |
| AC-04 | PDF 服务 | `curl http://127.0.0.1:3001/health` 返回 `UP` |
| AC-05 | 静态站 | `curl -I http://127.0.0.1/` 返回 `200`，HTML 含 SPA 挂载点 |
| AC-06 | 反代 | `curl http://127.0.0.1/api/system/health` 返回 `200` JSON |
| AC-07 | 公网可达 | 本机 `curl -I http://8.160.165.227/` 返回 `200` |
| AC-08 | 注册登录闭环 | 浏览器完成注册 → 登录 → 进入工作台，刷新后会话保持 |
| AC-09 | 资料库 CRUD | 新建职业资料成功，列表与详情数据一致 |
| AC-10 | 中文 PDF | 触发一次 PDF 导出，中文标题无方块/乱码 |
| AC-11 | AI 闭环（可选） | 配置 Key 后：JD 选材 → 确认快照 → 生成草稿 → 逐项审核 全链路成功 |
| AC-12 | 重启韧性 | `systemctl restart` 两服务后，AC-01~AC-05 仍通过 |
| AC-13 | 无敏感外泄 | 端口扫描确认 8080/3001/3306 对外不可达；`.env` 权限 600 |

### 实测结果（2026-09-23 执行完毕）

| 编号 | 结果 |
| --- | --- |
| AC-01 ~ AC-10, AC-12, AC-13 | ✅ 全部通过 |
| AC-11 AI 闭环 | ❌ **被账号额度阻塞**：百炼返回 `HTTP 403 / AllocationQuota.FreeTierOnly`（免费额度耗尽，需充值或关闭「仅用免费额度」模式）。密钥本身有效，非代码或部署问题 |

完整验收明细、操作命令与故障处置已整理为独立运行手册：[`docs/DEPLOYMENT_DIRECT.md`](../DEPLOYMENT_DIRECT.md)。

执行过程中额外发现并处置的 3 个未预判问题：

1. `prod` profile 与 HTTP 明文互斥（应用拒绝启动）→ 见 §7「已实测确认的硬约束」，改用默认 profile。
2. 部署主机 Node 不在 `/usr/bin`（实际 `/usr/local/bin/node`），`ExecStart=/usr/bin/npm` 触发 `status=203/EXEC` → 改为直接调用 node。
3. 最初以 PowerShell 编写部署入口无法在本机完整验证 → 改写为 bash 版 `scripts/deploy-direct.sh`（Git Bash 下已实测跑通），删除未验证的 `.ps1`。

---

## 6. 回滚

| 场景 | 动作 |
|---|---|
| 新版本 API 异常 | `systemctl stop api` → 换回上一版 jar（保留 `.bak`）→ `systemctl start api` |
| Flyway 迁移不兼容 | **不使用 `flyway clean`**。从 `backups/` 恢复 mysqldump 后回退 jar |
| 前端产物异常 | 保留 `app/web` 上一版目录 `web.bak`，`mv` 回退即可 |
| 整体放弃 | `systemctl disable --now` 两个单元 + `apt purge` 三件套；数据库与 `/opt/intelligent-resume` 保留待人工确认 |

部署前先做一次基线备份：`mysqldump --single-transaction`（首次部署库为空，仅作流程建立）。

---

## 7. 风险与已知坑（提前规避）

| 风险 | 规避手段 |
|---|---|
| **无 swap，OOM 会杀进程** | JVM 堆封顶 1G、MySQL buffer 512M；如需再加 `swapfile 2G`（见决策 D4） |
| Chromium 缺库导致 PDF 500 | D1 一次性装齐运行库；**必须装 `fonts-noto-cjk`**，否则中文出方块 |
| Node 26 与 Puppeteer 25.3 兼容性 | 服务器实测 `npm ci` + puppeteer 自带 Chrome 可启动；若失败改用 `npx puppeteer browsers install chrome` 指定版本 |
| pdf-service 的 Token 只认环境变量 | 用 systemd `EnvironmentFile` 或 `npm start` 的 `--env-file`，不依赖 dotenv |
| `COOKIE_SECURE=true` 导致刷新失效 | HTTP 模式显式设 `false` |
| 安全组未放行 80 | 列为 D7 前置，需你确认 |
| 服务器时区非 Asia/Shanghai | `timedatectl set-timezone Asia/Shanghai`，与 JDBC `serverTimezone` 对齐 |
| 构建期内存峰值 | 构建与运行**分时进行**，不在跑服务时构建 |
| 密钥落盘 | `.env` 权限 600；不上传 Git；文档中不出现真实值 |

### 已实测确认的硬约束（部署时发现，原方案未预判）

**`prod` profile 与 HTTP 明文访问互斥，应用会拒绝启动。**

- 证据：`server/src/main/java/com/intelligentresume/config/ProductionConfigurationValidator.java` 标注 `@Profile("prod")`，其 `validate()` 在 `COOKIE_SECURE != true` 时抛
  `IllegalStateException: COOKIE_SECURE must be true when the prod profile is active`。
- 矛盾点：`Secure` 属性的 Cookie 在明文 HTTP 下不会被浏览器保存，因此「prod + HTTP」在语义上不可同时成立。
- 处置：本测试环境（D1 决策为 HTTP 80）**使用默认 profile**，不启用 `prod`。
  默认 profile 已具备 `application.yml` 基础段声明的 actuator 暴露与 health 探针，连接串、密钥、CORS、PDF 与百炼配置全部由 `.env` 显式提供。
- 代价与补偿：默认 profile 保留了开发兜底值，若 `.env` 未生效会静默回退。补偿手段是运行期端到端验证——
  CORS 生效可通过浏览器跨源请求验证，PDF Token 生效可通过导出验证，任一未生效都会在 AC-08 / AC-10 立即暴露。
- 升级路径：若后续改走 HTTPS 自签名，应切回 `prod` profile 并将 `COOKIE_SECURE` 设为 `true`，即可重新获得该校验器的生产级防护。

---

## 8. 影响范围

**新增（不影响现有代码运行）**
- `scripts/Deploy-DirectUpload.ps1`（或 `.sh`）—— 一键打包上传 + 构建 + 重启
- `deploy/systemd/intelligent-resume-api.service`
- `deploy/systemd/intelligent-resume-pdf.service`
- `deploy/nginx/host.conf` —— 宿主机版 nginx 配置（不改现有 Docker 版）
- `docs/DEPLOYMENT_DIRECT.md` —— 本方案落地后的操作手册

**不动**
- `server/`、`web/`、`pdf-service/` 任何业务代码
- `docker-compose*.yml`、`Dockerfile`、现有 `deploy/nginx/{edge,web}.conf`
- `docs/DEPLOYMENT.md`（Docker/ACR 路线保留）

---

## 9. 已确认决策（2026-09-23）

| 编号 | 决策点 | 结论 |
|---|---|---|
| **D1** | 访问协议 | 采 **HTTP 80**（无证书；`COOKIE_SECURE=false`） |
| **D2** | 构建位置 | 采 **服务器构建**（JDK 17 + Maven；绕开本机 Maven classworlds 故障） |
| **D3** | 百炼 Key | **配置真实 Key**，首次仅跑 1 次生成以控制额度 |
| **D4** | swap | **加 2G swapfile**（无 swap 时 OOM 会直接杀进程） |
| **D5** | 脚本化入仓 | **做**（部署脚本 + systemd 单元 + 宿主机 nginx 配置一并入库） |

原建议与结论一致，方案按以上决策执行。

---

## 10. 变更记录

| 日期 | 变更内容 | 原因 | 影响范围 |
|---|---|---|---|
| 2026-09-23 | 初版方案 | 用户要求直接上传部署至 8.160.165.227 并先出方案 | 仅新增部署资产，不动业务代码 |
