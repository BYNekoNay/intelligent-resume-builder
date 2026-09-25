# ADR-007: 测试环境迁入与另一项目共用的主机

## Status: Accepted (2026-09-25)

## Background

测试环境所在服务器 `8.160.165.227`（阿里云 · Ubuntu 22.04 · 4 vCPU / 7.5 GiB）**到期停机**，
需迁至新服务器 `101.35.239.218`（腾讯云 · Ubuntu 26.04 LTS · 4 vCPU / **3.6 GiB**），
继续使用「源码上传 + 服务器原生进程」的直连部署方式。

**侦察发现新机不是一台空机器**，带来了原方案未覆盖的三个约束：

| 侦察事实 | 与原方案的冲突 |
| --- | --- |
| 同机已在运行另一个项目 `educational-administration`（艺培通，Docker + Caddy 4 容器，约 935 MiB） | Caddy 独占 `0.0.0.0:80/443`，**智历 nginx 若监听 80 必然 EADDRINUSE 启动失败** |
| 系统自带 MySQL 版本为 **8.4.11**（新机 Ubuntu 26.04） | 原手册的 `IDENTIFIED WITH mysql_native_password` 在 8.4 已不加载，会报 `ERROR 1524` |
| 内存从 7.5 GiB 降到 **3.6 GiB**，且已被对方占用约 935 MiB | 可用仅约 2.0 GiB，而智历需 JVM + MySQL + Chromium 三者共存 |

## Decision

1. **公网入口改用独立端口 8088**，不复用对方的 Caddy。
   nginx 站点 `listen 8088`（去掉 `default_server`），删除 nginx 自带 default 站点，
   公网地址为 `http://101.35.239.218:8088/`，需在腾讯云安全组放行 TCP 8088。
2. **独立安装系统级 MySQL 8.4 实例**，不复用对方已运行的 `mysql:8.0` 容器；
   认证插件使用 8.4 默认的 `caching_sha2_password`。
3. **数据库为空库重建**，不做数据迁移（旧机已到期，且该环境本就声明为可随时重置的测试环境）。
4. 为适配更小的内存，**下调资源上限**：JVM 堆 `-Xmx1024m → -Xmx768m`、
   MySQL `innodb_buffer_pool_size 512M → 192M`（8.4 按 chunk 取整后实际生效 256M）、
   `max_connections 100 → 50`、`performance_schema=OFF`。
5. **Chromium 改为预置**，不走 puppeteer 自带下载（见下）。

### 被否决的备选方案

| 方案 | 否决理由 |
| --- | --- |
| 复用对方 Caddy，加子路径或子域名反代智历 | 需要改另一个项目的容器配置与前端 `base` 路径；违反"互不影响"的最小耦合原则，且对方重启会牵连智历 |
| 复用对方的 `mysql:8.0` 容器 | 省约 400 MiB 内存且版本与原环境一致，但两项目共用同一实例后，对方的重启/迁移/数据操作会互相牵连；隔离性代价高于内存收益 |
| 保持 nginx 监听 80，把 Caddy 挪走 | 直接改动另一项目的线上入口，风险与收益完全不成比例 |

## Consequences

**正面**
- 两个项目**零配置耦合**：各自独立 nginx/Caddy、独立数据库、独立进程与服务单元；任一侧重启不影响另一侧。
- 实测确认艺培通在迁移全程保持 `Up (healthy)`、其入口 `http://127.0.0.1/` 返回 200，未被影响。
- 独立 MySQL 使智历的 schema 演进（Flyway 前向迁移）不受对方数据库版本约束。

**负面（必须承认的代价）**
- **内存是硬约束**：3.6 GiB 总量下，两个项目合计已接近物理上限。JVM 堆只能给 768m，
  且必须遵守"构建与运行不同时进行"。**若智历需要更多资源，正确做法是迁到独立主机，而不是在本机抢配额。**
- 公网入口带非标准端口（`:8088`），对外分享链接时需注意；且依赖云安全组正确放行，否则表现为"本机全绿、公网访问不通"。
- 本次迁移**未做数据迁移**，历史测试数据与账号全部丢失，需重新注册。

## 迁移中暴露并已固化的坑

这些坑的共同特征是**表现为"部署成功"但实际不可用**，故记录在此以免重犯：

| 坑 | 表现 | 处置 |
| --- | --- | --- |
| MySQL 配置文件权限 | `umask 077` 使配置文件成为 `600 root:root`，mysqld 以 `mysql` 用户读不到 → **静默回退编译默认值**，而 `systemctl is-active` 仍为 active | 强制 `chmod 644`，并用 `SELECT @@...` 核对实际生效值 |
| puppeteer 配置变量不存在 | 写 `~/.npmrc` 与 `export PUPPETEER_DOWNLOAD_BASE_URL` 均无效，仍直连 Google 源（**实测 95KB/s，292MB 需近 1 小时**） | 新增 `pdf-service/scripts/preseed-chromium.sh`：问 puppeteer 自己 `executablePath()` 要哪条路径，再从镜像并行分块下载（实测 1.5–1.8MB/s，187s 完成） |
| Chromium 缓存目录布局 | 布局为 `chrome/linux-<版本>/`，带平台前缀；按旧约定放 `chrome/<版本>/` 会被无视并报 "Could not find Chrome" | 预置脚本一律以库报告的路径为准，不硬编码 |
| 构建期与运行期用户不同 | 部署用户 `ubuntu`（HOME=`/home/ubuntu`）vs 服务用户 `root`（HOME=`/root`）→ 构建期下好的浏览器运行期找不到 | 统一 `PUPPETEER_CACHE_DIR=/opt/intelligent-resume/chromium-cache`，并写入运行配置 |
| 非 root 部署用户 | 远程脚本直接调 `systemctl restart` → `Access denied ... requires interactive authentication`，**失败发生在第 5 步**（构建已全部成功，易误判为部署完成） | 脚本改为检测 `id -u`，非 root 时经 `sudo -n` 执行，并在无免密 sudo 时提前报错退出 |
| 换行符判断不可靠 | 用 `grep -c $'\r' 文件` 判断 CRLF，在本机 Git Bash 下会匹配**每一行**，把纯 LF 文件误报为"全程 CRLF"，据此误判"部署资产需要转码"（曾据此在文档里写下错误结论，事后用 `tr -cd '\r' \| wc -c` 与 `git ls-files --eol` 复核为 CR=0） | 判据改为 `tr -cd '\r' < 文件 \| wc -c` 或 `git ls-files --eol`；实测 `deploy/` 资产为纯 LF，无需转码 |
| 自检假阳性 | 部署脚本的自检 URL 写死 80 端口，在新环境打到了同机另一个项目上，返回 200/404 却"看起来正常" | 自检带上 `PUBLIC_PORT`，并断言返回体的特征字段（`service: intelligent-resume-server`），不只看 HTTP 状态码 |
| Ubuntu 26.04 包名变化 | `libasound2` / `libatk1.0-0` / `libcups2` / `libatspi2.0-0` 均改带 `t64` 后缀 | 安装清单已按实测包名改写 |

## Related ADRs

- ADR-001 / ADR-002（百炼额度与模型链）：本环境沿用同一份 `BAILIAN_MODEL_CHAIN` 配置，未做变更
- `docs/DEPLOYMENT_DIRECT.md`（本决策的操作落地与验收清单）
