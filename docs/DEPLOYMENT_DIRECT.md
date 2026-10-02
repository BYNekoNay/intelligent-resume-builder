# 直接上传部署手册（非 Docker）

> 适用场景：单台 ECS 上以**源码上传 + 服务器原生进程**方式运行智历，不使用 Docker。
> 当前落地的测试环境：`101.35.239.218`（腾讯云 · Ubuntu 26.04 LTS · 4 vCPU / 3.6 GiB · 公网入口 `:8088`）
> 与本文并列的 [部署运行手册](./DEPLOYMENT.md) 描述的是 Docker + ACR 镜像路线，两条路线互不影响。
>
> 📌 **迁移记录**：本环境于 2026-09-25 从旧环境 `8.160.165.227`（阿里云 · Ubuntu 22.04 · 4 vCPU / 7.5 GiB）迁入，
> 原因是旧服务器到期。**迁移后数据库为空库重建**（未做数据迁移），用户需重新注册。
> 迁移中出现的新坑已补入 §8；各节命令均已按 Ubuntu 26.04 的实际包名与行为改写。

> **⚠ 环境属性声明（务必先读）**
> 该环境是**可随时重置的测试环境**，不是准生产环境，请勿据此判断生产就绪度。
> - 库内数据无长期保留价值，可整体重建。当前 `intelligent_resume` 为空库起步。
> - 测试账号命名前缀：`aitest*`（远程 AI 冒烟）、`e2e*`（端到端验证）、`local*` / `isolation*`（本地全流程验证脚本）。
>   可据此安全识别与清理，不必逐个确认。
> - **不得**在本环境承载真实用户数据（真实简历 / JD / 联系方式）。
> - 需要准生产环境时另行申请，不要在本环境上"加固成生产"。

> **⚠ 与同机另一个项目共存（务必先读）**
> 这台主机同时承载**另一个项目** `educational-administration`（艺培通），以 Docker + Caddy 运行，占用
> **80/443 端口**与约 935 MiB 内存。因此：
> - 智历的 nginx **必须监听 8088**，不能监听 80 —— 否则会因 `EADDRINUSE` 启动失败；
> - 智历**不能**改动对方的 compose / Caddyfile / 容器网络；
> - 内存总盘只有 3.6 GiB，两个项目合计已接近物理上限，**任何一侧显著增配都会挤压另一侧**（见 §8）。


---

## 1. 拓扑与端口

```text
 公网 :8088 ───────────►┌──────────────────────────────────────┐
                        │ nginx（智历）                         │
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
        │ MySQL 8.4 :3306       │            │ /opt/.../chromium-cache │
        │ 仅 127.0.0.1          │            │ （预置，与用户无关）      │
        └───────────────────────┘            └─────────────────────────┘

 ⚠ 同一台主机上另有「艺培通」项目（Docker + Caddy）独占 0.0.0.0:80 与 :443，
   与上图的智历链路**完全隔离**，互不代理、互不依赖。
```

| 服务 | 端口 | 绑定 | 公网 |
| --- | --- | --- | --- |
| nginx（智历） | 8088 | `0.0.0.0` | 开放（安全组需放行 TCP **8088**） |
| API | 8080 | `127.0.0.1`（由 `SERVER_ADDRESS` 控制） | 关闭 |
| pdf-service | 3001 | `127.0.0.1`（由单元内 `PDF_SERVICE_HOST` 控制，见 §8） | 关闭 |
| MySQL | 3306 | `127.0.0.1` | 关闭 |
| Caddy（艺培通，**非本项目**） | 80 / 443 | `0.0.0.0` | 开放，本项目不触碰 |

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
├── chromium-cache/            puppeteer 的 Chromium（643M，**部署不覆盖、与登录用户无关**）
├── logs/
├── backups/
├── secrets.env                JWT_SECRET / PDF_SERVICE_TOKEN / MYSQL_PASSWORD（600）
├── live-ai.env                BAILIAN_*（600，仅首次需要）
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
timedatectl set-timezone Asia/Shanghai   # 本环境安装时已是 Asia/Shanghai

# ⚠ 本机已有 2 GiB swap（/swap.img 由云镜像自带），无需再建；用 `swapon --show` 确认即可。
# 旧环境 7.5 GiB 无 swap 的机器才需要手工补 swap。

apt-get update
# ⚠ Ubuntu 26.04 的包名变化（按旧清单照抄会失败）：
#   libasound2      → libasound2t64
#   libatk1.0-0     → libatk1.0-0t64
#   libatk-bridge2.0-0 → libatk-bridge2.0-0t64
#   libcups2        → libcups2t64
#   libatspi2.0-0   → libatspi2.0-0t64
#   另：libu2f-udev 在本发行版已不存在（仅安装 .deb 版 Chrome 时才需要，本项目不需要）
# 另：nodejs/npm 走发行版包（nodejs 22.22.1 + npm 9.2.0），Node 落在 /usr/bin/node
apt-get install -y \
  openjdk-17-jdk-headless maven nginx mysql-server unzip ca-certificates \
  fonts-noto-cjk fontconfig nodejs npm \
  libnss3 libatk1.0-0t64 libatk-bridge2.0-0t64 libcups2t64 libdrm2 libxkbcommon0 \
  libxcomposite1 libxdamage1 libxfixes3 libxrandr2 libgbm1 libasound2t64 \
  libpango-1.0-0 libcairo2 libxshmfence1 libatspi2.0-0t64 fonts-liberation libvulkan1
```

实测版本：openjdk 17.0.20.1 · Maven 3.9.12 · nginx 1.28.3 · MySQL **8.4.11** · Node 22.22.1 · npm 9.2.0。

> `fonts-noto-cjk` 是**必需项**：缺失时 PDF 的中文会渲染成方块字。
> 后 14 个 lib 是 Chromium 运行库，任缺一个都会让 `/render` 返回 500。

### 4.1.1 包管理器镜像（国内必需，实测差异巨大）

```bash
# npm：本机云镜像已预置（mirrors.tencentyun.com/npm，实测 0.13s）
npm config get registry

# Maven：默认 Central 实测 1.0–2.3s/请求，aliyun 镜像 0.17s，且拉 jar 时可达 13–27MB/s
mkdir -p ~/.m2
cat > ~/.m2/settings.xml <<'XML'
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <mirrors>
    <mirror>
      <id>aliyun-public</id>
      <url>https://maven.aliyun.com/repository/public</url>
      <mirrorOf>central</mirrorOf>
    </mirror>
  </mirrors>
</settings>
XML
```

> 加镜像后后端全量构建（`-DskipTests`）实测 **46s** 完成，依赖下载不再是瓶颈。

### 4.2 MySQL 初始化

```bash
# ⚠ 配置文件权限：必须先设 umask 或事后 chmod 644。
# mysqld 以 mysql 用户运行；若文件是 600 root:root（例如被 umask 077 影响），
# mysqld 读不到它会**静默回退到编译默认值**（表现为 buffer pool=128M、max_connections=151），
# 而且 `systemctl is-active mysql` 依然是 active，极易误判为配置已生效。
cat > /etc/mysql/mysql.conf.d/zz-resume.cnf <<'CNF'
[mysqld]
bind-address = 127.0.0.1
innodb_buffer_pool_size = 192M
performance_schema = OFF
max_connections = 50
character-set-server = utf8mb4
collation-server = utf8mb4_unicode_ci
CNF
chown root:root /etc/mysql/mysql.conf.d/zz-resume.cnf
chmod 644 /etc/mysql/mysql.conf.d/zz-resume.cnf
systemctl restart mysql

# 生效核对（不要只看 systemctl is-active）：
mysql -uroot --protocol=socket -e \
  "SELECT @@innodb_buffer_pool_size/1024/1024 AS pool_MB, @@max_connections, @@bind_address, @@performance_schema, @@character_set_server;"
# 实测：pool_MB=256（8.4 会按 chunk 向上取整到 256M）、max_conn=50、bind=127.0.0.1、pfs=0、charset=utf8mb4
```

建库与账号（**MySQL 8.4 的认证插件差异，照抄旧手册会失败**）：

```bash
mysql -uroot --protocol=socket <<'SQL'
CREATE DATABASE IF NOT EXISTS intelligent_resume
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'resume_app'@'127.0.0.1' IDENTIFIED BY '<随机口令>';
GRANT ALL PRIVILEGES ON intelligent_resume.* TO 'resume_app'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL

# 核对插件（应为 caching_sha2_password）：
mysql -uroot --protocol=socket -e \
  "SELECT user,host,plugin FROM mysql.user WHERE user='resume_app';"
```

> ⚠ **不要写 `IDENTIFIED WITH mysql_native_password`**：MySQL 8.4 已不再默认加载该插件，会直接报
> `ERROR 1524 Plugin 'mysql_native_password' is not loaded`。用默认的 `caching_sha2_password` 即可 ——
> Spring Boot 3.3 管理的 `mysql-connector-j` 8.3 原生支持它，配合连接串里的
> `allowPublicKeyRetrieval=true&useSSL=false` 可正常握手（已实测 TCP 登录成功）。

**不要手工建表**：表结构由应用启动时的 Flyway 前向迁移创建（当前 `V1` → `V25`，共 **25 个迁移 / 24 张业务表**）。
> 别被 `ls db/migration` 误导：它按字典序排序，`V10`~`V25` 会排在 `V2` 前面，
> 直接 `tail` 会看到 `V9` 而误以为只到 V9。用 `ls | sort -V` 或看 `flyway_schema_history` 的 count。

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
CORS_ALLOWED_ORIGINS=http://101.35.239.218:8088
# 本机 nginx（host.conf）是唯一入口并以 $remote_addr 覆写 X-Forwarded-For；
# 开启后限流按真实客户端 IP 分桶。不开启会退化为按 127.0.0.1 全局共享一个分桶
# （登录 10/min 等阈值全站共用）。默认 profile 不强制，但本部署应显式设为 true。
RATE_LIMIT_TRUST_FORWARDED_HEADERS=true
PDF_SERVICE_BASE_URL=http://127.0.0.1:3001
PDF_SERVICE_TOKEN=<secrets.env 的 PDF_SERVICE_TOKEN>
PDF_OUTPUT_DIR=/opt/intelligent-resume/app/api/pdf-output
BAILIAN_API_KEY=<live-ai.env>
BAILIAN_MODEL=qwen3.8-max
BAILIAN_MODEL_CHAIN=qwen3.8-max,glm-5.3,qwen3.8-27b,qwen3.8-2.4t-a95b,qwen3.8-max-0902,deepseek-v4.1-flash,deepseek-v4-pro-0813
BAILIAN_READ_TIMEOUT_S=300
AI_CHAIN_QUOTA_COOLDOWN_S=1800
AI_CHAIN_TRANSIENT_COOLDOWN_S=60
AI_CHAIN_TOTAL_BUDGET_S=600
```

> **链总预算（`AI_CHAIN_TOTAL_BUDGET_S`）**：单个模型的读超时（默认 300s）乘以链长度会放大成数十分钟，
> 而 worker 处理单条任务期间会一直占住线程，后续 AI 任务会排队阻塞。超出总预算即停止顺延并快速失败，
> 由 worker 的重试机制稍后再跑。取值应大于单次读超时、小于可接受的最坏排队时长。

> **`app/api/.env` 是运行期唯一生效来源（第六十七批实测）**：`application.yml` 用
> `spring.config.import: optional:file:../.env[.properties],optional:file:.env[.properties]` 读取它，
> 而 api 单元的 `WorkingDirectory=/opt/intelligent-resume/app/api` ⇒ 生效文件即 `app/api/.env`
> （进程环境里**没有任何** `BAILIAN_*`，单元也**没有** `EnvironmentFile=`）。
> ⚠ 同机上的 `live-ai.env` **运行期不再被读取**（仅在首次部署时提供密钥、由人抄进 `.env`）——
> 第六十七批实测发现两者已经**漂移**（`live-ai.env` 停在 7 模型、`.env` 是 8 模型），
> 足以说明「只改 `live-ai.env` 不会有任何效果」。**改链一律改 `app/api/.env` 并重启**，
> 核对方式见下（启动日志的 `modelChain=` 与 `resume_ai_model_chain_available` 指标）。
>
> 改动后的**运行时核对**（不要只看文件）：
> ```bash
> sudo systemctl restart intelligent-resume-api
> sudo journalctl -u intelligent-resume-api --since "3 min ago" | grep -o "modelChain=\[[^]]*\]"
> curl -s http://127.0.0.1:8080/actuator/prometheus | grep resume_ai_model_chain_available
> ```

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
# 监听地址由 systemd 单元的 Environment=PDF_SERVICE_HOST=127.0.0.1 注入（进程环境优先于 --env-file，
# 故这里不必再写；若绕过单元手工 `node src/server.js` 启动，务必自行加上这一行）。
PDF_SERVICE_TOKEN=<与 api 一致>
# Chromium 缓存目录：必须与部署时下载到的位置一致，且**不能依赖 HOME**
# （服务以 root 运行，HOME=/root，而部署用户是 ubuntu，HOME=/home/ubuntu；
#   用默认的 ~/.cache/puppeteer 会导致运行期找不到浏览器）
PUPPETEER_CACHE_DIR=/opt/intelligent-resume/chromium-cache
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
rm -f /etc/nginx/sites-enabled/default      # ⚠ 必须删：默认站点监听 80，会与艺培通 Caddy 冲突
nginx -t && systemctl reload nginx
```

> 补充（**第六十一 / 六十五批后已过时**）：上述 `install` 步骤现在只用于**首次**安装。
> 日常迭代由 `scripts/deploy-direct.sh` 完成 —— 它已把 `deploy/` 纳入打包，并由
> `deploy-direct.remote.sh` **幂等同步** nginx 站点配置（第六十一批）与 **systemd 单元**
> （第六十五批：仅在内容变化时 `install` + `daemon-reload`）。故改 `deploy/` 后跑一次脚本即可生效，
> 不需要手工上传。
> `.gitattributes` 已把 `*.conf` / `*.service` / `*.sh` 声明为 `eol=lf`，
> **实测工作区确为纯 LF**（`tr -cd '\r' < file | wc -c` = 0）；脚本侧另做一次 `sed 's/\r$//'` 兜底
> —— CR 落进单元会让 systemd 报 "Unknown key name"、落进 nginx 配置会报 "unexpected \"{\""。
> ⚠ **不要用 `grep -c $'\r' 文件` 判断 CRLF**：该写法在本机 Git Bash 下会匹配到**每一行**
> （实测把一个纯 LF 的新脚本报成"121 行含 CR"），据此会误判"部署资产是 CRLF、需要转码"。
> 可靠判据：`tr -cd '\r' < 文件 | wc -c`（为 0 即无 CR）或 `git ls-files --eol 文件`。
>
> ⚠ **首次安装 nginx 时它会因 80 端口被占而启动失败**（`systemctl is-active nginx` = failed）。
> 这是预期现象：先按上面装好站点、删掉 default，再 `systemctl reset-failed nginx && systemctl enable --now nginx` 即可。

### 4.6 预置 Chromium（**国内环境必做，否则部署卡近 1 小时**）

puppeteer 不会在运行期下载浏览器，而是在 `npm ci` 的安装脚本里下载。国内直连其默认源
（`storage.googleapis.com`）实测持续速度仅约 **95KB/s**，且要下两个包（chrome 178MB +
chrome-headless-shell 114MB）合计约 292MB —— **接近 1 小时**，而且中途失败会留下残缺缓存。

```bash
cd /opt/intelligent-resume/src/pdf-service
PUPPETEER_SKIP_DOWNLOAD=true npm ci --no-audit --no-fund
PUPPETEER_CACHE_DIR=/opt/intelligent-resume/chromium-cache bash scripts/preseed-chromium.sh
```

`scripts/preseed-chromium.sh`（随 `pdf-service/` 一起上传）做的事：问 puppeteer 自己
`executablePath()` 期望哪条路径 → 从 npmmirror 并行分块下载该版本 → 解压到该路径。幂等，可重复执行。
实测：**并行 8 片后持续速度从 255KB/s 提升到 1.5–1.8MB/s，292MB 共 187 秒完成。**

> ⚠ **两个已实测确认的反直觉事实，别再走弯路**：
> 1. **`PUPPETEER_DOWNLOAD_BASE_URL` 这个环境变量在 puppeteer 25 里根本不存在**。
>    源码里只有 `PUPPETEER_CACHE_DIR` / `EXECUTABLE_PATH` / `REVISIONS` / `SKIP_DOWNLOAD` / `TMP_DIR` /
>    `LOGLEVEL` 等。所以「写 `~/.npmrc` 的 `puppeteer_download_base_url`」和
>    「export `PUPPETEER_DOWNLOAD_BASE_URL`」**都是无效操作** —— 两次实测连接目标仍是 Google 的 142.250.x.x。
> 2. **缓存目录布局带平台前缀**：`<缓存>/chrome/linux-150.0.7871.24/chrome-linux64/chrome`。
>    若按旧约定手工放成 `chrome/150.0.7871.24/`，puppeteer 会无视它并报 "Could not find Chrome"。
>    因此预置脚本**一律以 puppeteer 自己报告的路径为准**，不硬编码版本与布局。

### 4.7 运行期身份一致性（易被忽略的静默失败点）

`intelligent-resume-pdf.service` **没有** `User=`，即以 **root** 运行。而部署用户是 `ubuntu`。
两者 `HOME` 不同，`~/.cache/puppeteer` 也不同 —— 若不做处理，构建期下好的浏览器运行期找不到，
表现为「部署脚本全绿、只有导出 PDF 时才报 Could not find Chrome」。
故统一使用 `/opt/intelligent-resume/chromium-cache`，并同时写入 `app/pdf-service/.env`；
部署脚本会在同步产物后**自动补齐**该配置项（幂等）。

---

## 5. 日常迭代部署（一条命令）

```bash
bash scripts/deploy-direct.sh
```

脚本做的事：本机打包源码（排除 `node_modules` / `target` / `dist` / `.env`，并做泄露校验）→ 上传 →
解压 → 服务器端 `mvn clean package`（aliyun 镜像）+ `npm ci && npm run build`（含 i18n 与
draft-fields 两道静态门禁）→ **PDF 依赖 `PUPPETEER_SKIP_DOWNLOAD=true npm ci` + 预置 Chromium +
真实启动一次 Chromium 自检** → 原子替换产物 → **同步 nginx 站点配置**（`deploy/nginx/host.conf` +
`security-headers.conf` 片段；先落位片段再覆盖站点文件，`nginx -t` 校验通过才 reload）→
**同步 systemd 单元**（`deploy/systemd/*.service`；仅在内容变化时安装 + `daemon-reload`）→
重启服务 → 等待 readiness → **部署后探针**（17 项：行为断言 + 生效配置与仓库的一致性 + 应用加载配置与磁盘 `.env` 的一致性）→
输出健康检查。

> **nginx 配置已纳入脚本（2026-10-01 第六十一批）**。此前它**只存在于 §4.5 的手工流程**里，
> 于是仓库中的 nginx 改动不会随部署生效 —— 实测代价有两条：① 静态资源 gzip（第三十五批）
> 从未生效（服务器仍是 nginx 自带的 `gzip on;`，只压 `text/html`、不压 js/css，
> `curl -I -H 'Accept-Encoding: gzip' /assets/*.js` 看不到 `Content-Encoding`）；
> ② `client_max_body_size` 停留在 **5m**（第二十九批已改为 6m），导致 5.5MB 上传被 nginx 以
> **HTML 错误页** 413 拒绝、而非应用的统一信封。两条都在 2026-10-01 的云端回归中被实测抓到，
> 现已随本脚本同步并复验通过（`suite_core` 28/28）。

> **systemd 单元已纳入脚本（2026-10-01 第六十五批）**。这是**同源的链路洞**：单元同步此前
> 也只在 §4.5 的手工流程里，于是 **第四十批给 pdf 单元加的 `Environment=PDF_SERVICE_HOST=127.0.0.1`
> 从未在真实环境生效** —— 服务器单元一直停留在 2026-09-25 的版本，PDF 服务实际监听 `*:3001`。
> 该暴露面**只剩云安全组兜底**，而安全组是云侧规则、在服务器内部看不见，所以从外部探测
> 「通不通」并不能证明绑定参数生效。本轮部署时被新增的探针断言（§5.2 第 6/7 节）实测抓到：
> 修复前探针 **14/16**（精确指出单元差异 `> Environment=PDF_SERVICE_HOST=127.0.0.1` 与
> `PDF 3001 监听在非回环地址：*:3001`），修复后 **16/16**、`ss -lntp` 为 `127.0.0.1:3001`。

默认值已对应当前环境：`SERVER=101.35.239.218`、`SSH_USER=ubuntu`、`PUBLIC_PORT=8088`。
可用环境变量覆盖：`SERVER`、`SSH_USER`、`SSH_KEY`、`REMOTE_ROOT`、`PUBLIC_PORT`、`PUPPETEER_CACHE_DIR`。

> Chromium 自检失败会**中止部署**（而非留一个能跑但导出必失败的 PDF 服务）—— 这是刻意的：
> 该失败在旧环境上只会以「导出 PDF 报 Could not find Chrome」的形式在运行期暴露，很难定位。

### 5.1 测试资产随包上传（2026-09-25 新增）

`test-fixtures/`（仓库根目录，当前只有 1 个合成简历 JSON）属**测试资产而非运行资产**，
但它**必须随包上传** —— 因为 `pdf-service/test/templates.test.js` 与 `web/e2e/workflow.spec.ts`
都以 `new URL('../../test-fixtures/resume-all-sections.json', import.meta.url)` 解析它，
而该路径**相对于仓库根**。不上传时服务器侧这两套测试会直接 `ENOENT` 跑不起来
（此前的实际表现：`templates.test.js` 整个文件级失败，`npm test` 只跑出 8 个用例）。

落位在 **`/opt/intelligent-resume/src/test-fixtures/`**，即源码树内。

> ⚠ **因此服务器侧跑测试必须从源码树 `src/` 运行，而不是 `app/`**：
>
> ```bash
> cd /opt/intelligent-resume/src/pdf-service && PUPPETEER_SKIP_DOWNLOAD=true npm test
> ```
>
> 部署包会把 `pdf-service/`（含 `test/`）同步到 `app/pdf-service/`，但**不会**把
> `test-fixtures/` 放进 `app/`，所以从 `app/pdf-service/` 跑测试仍会 ENOENT。
>
> 其他两套测试在服务器上的可行性：
> - **web E2E**（`npx playwright test`）：另需 Playwright 浏览器依赖，服务器未预装，当前不可跑；
> - **后端 `mvn test`**：在 3.6 GiB 内存的宿主机上与另一个项目共存时有 OOM 风险，
>   建议仍在本机执行（本机实测 652 测试约 1 分 40 秒）。

> 服务器侧逻辑在 `scripts/deploy-direct.remote.sh`，可单独在服务器上执行：
> `bash /opt/intelligent-resume/deploy-direct.remote.sh`

---

### 5.2 部署后探针（2026-10-01 第六十二批新增）

`scripts/probe-deployment.sh` 是部署流程的**最后一步**（部署脚本自动调用），也可单独运行：

```bash
bash scripts/probe-deployment.sh                       # 默认打当前测试环境
bash scripts/probe-deployment.sh http://host:port      # 指定入口
SSH_HOST= bash scripts/probe-deployment.sh             # 跳过配置一致性对比（无 SSH 时）
```

两类断言，缺一不可：

| 类别 | 内容 |
| --- | --- |
| **A. 行为断言** | HTTP 可观察：首页 200 · 安全头 4 条 · 匿名 health 收敛（无 `checks`）· `health/detail` 401 · **静态资源 gzip** · **5.5MB 上传已穿过 nginx** · 哈希资源长缓存。**在服务器上**直接观察：API `8080` 与 PDF `3001` 的监听地址必须**仅绑回环**（第六十五批新增） |
| **B. 配置一致性** | ① 服务器生效的 **nginx 配置** vs 仓库 `deploy/nginx/host.conf`；② 服务器生效的 **systemd 单元** vs 仓库 `deploy/systemd/*.service`（第六十五批新增）；③ **应用实际加载的配置** vs 磁盘 `app/api/.env`（第六十七批新增）—— 归一化后逐行/逐值比对，**通用地**发现「配置改了没生效」的漂移 |

探针**失败不掩盖「部署已完成」这一事实**，但以非零退出码结束 —— 避免「部署成功」的假阳性。

> **① ② 是「仓库改对了、环境是旧的」；③ 是反方向 ——「文件改对了、进程是旧的」**（改了 `.env` 忘了重启）。
> 三条合起来才覆盖住「配置真的有生效」这一整类问题。

> B 类断言的存在理由：第六十一批实测到两条 nginx 配置改动（`client_max_body_size` 5m→6m、
> 静态资源 gzip）在仓库早已改好、CI 全绿，却因部署链路不含 `deploy/` 而**从未生效**。
> 行为断言能抓到它们**造成的结果**，配置一致性断言则能**通用地**抓到这一类（不限于已知的两条）。
>
> 第六十五批把同一手法扩到 systemd：**行为断言是「服务器上的监听地址」而不是「从外部能否连通」**
> —— 因为该端口此前只由云安全组拦截，外部探测会因为安全组而"看起来正常"，形成假阳性；
> 只有 `ss -lntp` 才能证明绑定参数真的生效。

---

## 6. 验收结果（2026-09-25 · 新环境 `101.35.239.218` · 入口 `:8088`）

> 旧环境 `8.160.165.227` 的验收结论（原 AC-01~AC-13 表）已随该机下线失效，不再维护；
> 如需追溯见 git 历史。**下表每一条都附实测证据，未验证的项明确标出，不用"通过"含糊带过。**

| 编号 | 检查项 | 结果 | 证据 |
| --- | --- | --- | --- |
| AC-01 | 四个服务状态 | ✅ | `mysql` / `nginx` / `intelligent-resume-api` / `intelligent-resume-pdf` 均 `active` |
| AC-02 | API readiness | ✅ | `GET 127.0.0.1:8080/actuator/health/readiness` → `{"status":"UP"}` |
| AC-03 | 业务健康契约 | ✅ | 经 8088 的 `GET /api/system/health` → `code:0, status:UP`，8 项 capabilities（resume / ai-tasks / ats / applications / communications / interviews / imports / pdf-export） |
| AC-04 | 静态站 | ✅ | `GET http://127.0.0.1:8088/` → `200 text/html`；SPA 内带哈希的 `index-*.js` → 200 |
| AC-05 | nginx 反代 | ✅ | `GET http://127.0.0.1:8088/api/system/health` → 200 |
| AC-06 | DB 迁移 | ✅ | `flyway_schema_history` applied=**25**、最高版本 **V25**；`intelligent_resume` 共 **24** 张表 |
| AC-07 | 注册登录闭环 | ✅ | 经 8088 注册 → `code:0` 并返回 `accessToken`；`/api/auth/me` 带 token → 200 且返回本人；不带 token → **401** |
| AC-08 | 职业资料 CRUD | ✅ | `POST /api/career-materials`（`materialType=WORK_EXPERIENCE`）→ `code:0`；列表回读 1 条、标题一致、`evidenceReady:true` |
| AC-09 | 中文 PDF | ✅ | **7/7 模板全部 200**（classic / modern / minimal / ats / executive / compact / academic），响应头 `%PDF-`、字节 88–92KB，且每份**内嵌 `NotoSansCJK` 字体子集**（2–3 处）→ 中文非方块字 |
| AC-10 | 真实 AI 调用 | ✅ | 本机直连百炼兼容接口，链首 `qwen3.8-max` 返回内容正常；`usage.reasoning_tokens=16`（顺带再证推理型模型的开销特征） |
| AC-11 | 部署可复现 | ✅ | `bash scripts/deploy-direct.sh`**完整 7 步全绿**：Maven 46s、vite 7.9s、Chromium 预置命中并跳过下载、Chromium 真实启动自检通过（PDF 8285 字节） |
| AC-12 | 安全边界 | ✅ | `8080` **仅回环**（`ss` 显示 `[::ffff:127.0.0.1]:8080`；内网 `10.0.4.17:8080` 与公网均不可达）；`3001` / `3306` 公网不可达；`.env` 权限 600 |
| AC-13 | 与艺培通共存 | ✅ | 全程 4 个容器 `Up (healthy)`，其入口 `http://127.0.0.1/` → 200，未被迁移影响 |
| AC-14 | 公网可达（8088） | ❌ **未通过** | 绕过本机代理后 `curl --noproxy '*' http://101.35.239.218:8088/` → **连接超时**。腾讯云**安全组尚未放行 TCP 8088**，需在控制台添加规则后复测 |

### 6.1 本轮纠正的两处误判（避免把错的结论留在文档里）

1. **部署脚本的自检 URL 打到了另一个项目上**。原脚本第 7 步用 `http://127.0.0.1/api/system/health`
   （即 **80 端口**）。旧环境智历独占 80 所以是对的；新环境 80 属于艺培通，于是自检返回了
   **艺培通的 404 JSON**，而第 5 步的 `curl http://127.0.0.1/` 更是拿到了**艺培通首页的 200** ——
   一个典型的"自检全绿但测的不是自己"的假阳性。已把两处自检改为带 `PUBLIC_PORT`（默认 8088）。
2. **本机存在全局代理** `HTTP_PROXY=http://127.0.0.1:49435`，导致首次公网探测得到 `502`，
   一度被误读成"公网可达但网关异常"。用 `--noproxy '*'` 复测才得到真相（连接超时 = 安全组未放行）。
   **凡是从本机探测公网地址，都应显式绕过代理。**

### 6.2 明确未覆盖的范围

- 未做**数据迁移**（旧机到期），未验证历史数据兼容性；
- 未跑**前端 E2E 套件**（`npx playwright test`）与后端全量单测（本机为生产部署机，未安装浏览器依赖）；
- 未验证真实用户身份在浏览器里走完整产品闭环（仍受"仅有合成数据"的既有局限约束）；
- `evidenceReady` 等业务语义仅验证"接口返回与回读一致"，未做业务规则级核验。

---

## 7. 百炼额度：**按模型**计量（原「账号级已耗尽」判断已更正）

> 本节曾得出「账号处于仅用免费额度模式且额度用尽，所有 AI 功能都会失败，**需充值或关闭该模式**」的结论。
> **该结论是错的**，已于同日实测更正。完整决策见 [ADR-001](./decisions/ADR-001-bailian-quota-is-per-model.md)。
> 保留原证据与推理，是为了让后来者能看到错在哪里。

### 7.1 原判断及其证据

2026-09-23 直接调用百炼兼容接口验证凭据：

```text
HTTP 403  (0.14s)
{"error":{"code":"AllocationQuota.FreeTierOnly",
          "message":"Free quota exhausted. To continue accessing the model on a paid basis,
                     please add funds or disable the \"use free tier only\" mode in the management console."}}
```

当时据此判定：密钥本身有效（无效密钥会返回 401 `InvalidApiKey`），是账号额度策略拒绝了请求，
因此所有 AI 功能都会失败，需充值或关闭「仅用免费额度」模式。

**推理错在哪**：只用了**一个**模型做验证，就把结论外推到了**整个账号**。

### 7.2 更正后的实测结果

逐个真实调用后发现：额度是**按模型**独立计量的。

| 模型 | 实测 |
| --- | --- |
| `qwen3.7-plus-2026-05-26`（当时唯一配置的模型） | 403 `AllocationQuota.FreeTierOnly` |
| `qwen-plus` | 403 `AllocationQuota.FreeTierOnly` |
| `qwen3.8-max` / `glm-5.3` / `qwen3.8-27b` / `qwen3.8-2.4t-a95b` / `qwen3.8-max-0902` / `deepseek-v4.1-flash` / `deepseek-v4-pro-0813` / `kimi-k3` | **200 正常** |

> **注（第六十七批）**：上表的「200 正常」是**探针请求**（`max_tokens` 很小）的结果。
> `kimi-k3` 在**本应用的真实请求**下必然 400 —— 它拒绝 `temperature` 参数，而本应用每次请求都带
> （ADR-005 §7.4），因此它是链上的**纯失败跳转节点**（每次多一次约 1s 的失败往返）。
> 已按 `DECISION-BRIEF` **E1** 从 `BAILIAN_MODEL_CHAIN` 移除（链长 8 → 7）。

**结论：不需要充值。** 真正的病根是**单模型硬编码** —— 把可用性绑死在某一个模型的额度上。
已通过可配置的**有序模型链**解决，配置项见 §4.4 的 `BAILIAN_MODEL_CHAIN`。

### 7.3 当前状态

- 模型链已上线并实测通过：**7 个模型**全部可用（第六十七批按 E1 移除 `kimi-k3`；此前为 8 个），7 类 AI 任务（选材 / 生成 / ATS 分析 / 沟通文案 / 内联润色 / 成果引导 / 模拟面试）真实跑通。
- 单模型额度耗尽会**自动跳过**并进入 30 分钟冷却，服务不中断。
- 链路可用性有告警覆盖：`AiModelChainExhausted`（全链失效，critical）与 `AiModelChainDegraded`（可用模型 ≤2，warning）。

### 7.4 仍然有效的运维提示

`/api/system/health` 的 `ai-provider` 项只检查「是否注册了可用 provider」（`providerRegistry.hasAvailableProvider()`），
**既不代表密钥可用、也不代表有额度**。为此已新增 `ai-model-chain` 检查项，回答"现在真的能不能调"；
全链失效时整体状态转 `DEGRADED`。即便如此，判断 AI 是否真可用最可靠的方式**仍是发起一次真实调用**。

---

## 8. 已知坑与残留风险

| 项 | 说明 | 处置 |
| --- | --- | --- |
| **`.env` 的键必须被 `application.yml` 的 `${NAME}` 占位符消费，否则被静默忽略** | 本项目的配置读取模式是「`application.yml` 写占位符 + `.env` 提供值」（如 `url: ${SPRING_DATASOURCE_URL:...}`）。**只把键写进 `.env` 而没有对应占位符的项不会生效**，且无任何日志提示。实例：`SERVER_ADDRESS=127.0.0.1` 曾被静默忽略（`application.yml` 里根本没有 `server.address` 项），导致 **API 监听 `*:8080` 并暴露到公网**（公网返回 401） | 已改为在单元里以 `Environment=SERVER_ADDRESS=127.0.0.1` 注入（走 `SystemEnvironmentPropertySource`，确定生效）。**新增任何配置项后，必须回到 `application.yml` 确认占位符存在**；改完用 `ss -lntp` 与真实请求核对，不要只看配置文件 |
| **安全组未放行 8088** | 服务器侧 nginx 已在 `0.0.0.0:8088` 正常服务（本机 200），但公网连接超时 —— 腾讯云安全组没有该端口规则 | 需在控制台放行 TCP 8088；放行后从**绕过代理**的客户端复测（本机 `HTTP_PROXY` 会干扰判断） |
| **部署脚本自检 URL 的端口** | 旧脚本自检打 80 端口，在本环境会打到同机另一个项目上并返回 200/404，形成"自检全绿但测的不是自己"的假阳性 | 已改为 `PUBLIC_PORT`（默认 8088）；**任何自检都要断言返回体的特征字段**（如 `service: intelligent-resume-server`），不能只看 HTTP 200 |
| **与艺培通共用主机** | 同机另一项目（Docker + Caddy）独占 80/443 与约 935MiB 内存 | 智历 nginx 固定监听 8088；安全的做法是**互不改配置**。需要更多资源时应迁机，而不是在本机抢 |
| **81 端口类冲突** | 装 nginx 时其默认站点会尝试绑定 80 → 启动失败（expected） | 装好后删 `sites-enabled/default`、站点改 8088，再 `reset-failed` + 启动 |
| **换行符判断的工具陷阱** | 用 `grep -c $'\r' 文件` 判断 CRLF，在本机 Git Bash 下会**匹配每一行** —— 一个纯 LF 文件（如刚生成的脚本）会被报成"121 行含 CR"，据此误判为"部署资产是 CRLF 需要转码"（本次真实踩到，并一度把错误结论写进本文档） | 判据改用 `tr -cd '\r' < 文件 \| wc -c`（为 0 即无 CR）或 `git ls-files --eol`。实测 `deploy/` 下 `.gitattributes` 声明的 `eol=lf` 生效，资产为纯 LF，**无需转码** |
| **配置生效性不能只看配置文件** | 多起"改了没生效"都表现为服务照常 active：MySQL 配置因文件权限静默回退默认值、`.env` 键因缺占位符被静默忽略、API 因未绑定回环而暴露公网 | 每项配置改完都要**用运行时事实核对**：`SELECT @@...`、真实请求的返回体、`ss -lntp` 的监听地址 |
| **puppeteer 配置变量不存在** | puppeteer 25 无 `PUPPETEER_DOWNLOAD_BASE_URL`，npmrc 与 env 两路都无效 | 只能靠 `PUPPETEER_SKIP_DOWNLOAD` + 预置脚本，见 §4.6 |
| **Chromium 缓存布局带平台前缀** | `chrome/linux-<版本>/`，不是 `chrome/<版本>/` | 预置脚本以 `executablePath()` 为准，勿手工摆 |
| **构建期/运行期用户不同** | 部署用户 ubuntu vs 服务用户 root，`HOME` 不同导致浏览器找不到 | 统一 `PUPPETEER_CACHE_DIR=/opt/intelligent-resume/chromium-cache`，见 §4.7 |
| **MySQL 配置文件权限** | 若为 `600 root:root`，mysqld 以 mysql 用户读不到 → **静默回退默认值**，而 `is-active` 仍为 active | 必须 `chmod 644`；改完用 `SELECT @@...` 核对实际生效值，不要只看服务状态 |
| **MySQL 8.4 认证插件** | `mysql_native_password` 在 8.4 已不加载，照旧手册建用户会 `ERROR 1524` | 用默认 `caching_sha2_password`；连接串保留 `allowPublicKeyRetrieval=true&useSSL=false` |
| **Ubuntu 26.04 包名** | `libasound2` / `libatk1.0-0` / `libcups2` / `libatspi2.0-0` → 均带 `t64` 后缀 | 见 §4.1 清单；`libu2f-udev` 已不存在 |
| Node 路径 | 本环境 Node 来自发行版包，位于 `/usr/bin/node`（旧环境为 `/usr/local/bin/node`） | 单元里已改为 `/usr/bin/node`；更换 Node 安装方式必须同步改单元，否则 `status=203/EXEC` |
| pdf-service 绑定 | 应用原用 `app.listen(port)` 未指定 host，实际监听 `0.0.0.0:3001`，是否可达只取决于安全组 | **已收敛（第四十批）**：`src/server.js` 支持 `PDF_SERVICE_HOST`，pdf 单元注入 `Environment=PDF_SERVICE_HOST=127.0.0.1`（与 api 单元的 `SERVER_ADDRESS` 同口径）。核对方式：启动日志的绑定地址与 `ss -lntp`（应为 `127.0.0.1:3001`）。容器路径刻意不设置该变量（API 容器需经 `pdf-service:3001` 访问）。门禁：`PdfServiceBindScopeContractTest`。⚠ **代码与单元都对了，但真实环境直到第六十五批才生效** —— 单元同步此前不在部署脚本内（同第六十一批的 nginx 洞），服务器实际监听 `*:3001` 直到本轮修复；见 §5 与 §5.2 第 6/7 节 |
| 默认 profile 兜底值 | 默认 profile 保留了开发兜底值，若 `.env` 加载失败会静默回退 | 已通过运行期端到端验证补偿（CORS 生效 → 浏览器可跨源；PDF Token 生效 → 导出成功）；这两项任一失败会立即暴露 |
| **内存偏紧** | 总 3.6 GiB：艺培通 ~935MiB + 系统 ~740MiB，剩余可用约 2 GiB；智历需 JVM(768m) + MySQL(256m buffer pool) + Chromium | JVM 堆已从 1024m 降到 `-Xmx768m`、MySQL buffer pool 降到 256M、2 GiB swap 兜底；**构建与运行不要同时进行**，也不要两侧同时跑重负载 |
| 时区 | 主机时区须为 `Asia/Shanghai`，与 JDBC `serverTimezone` 对齐 | 本环境安装时已是 Asia/Shanghai |

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
