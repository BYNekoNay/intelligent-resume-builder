# 云端端到端验证报告（2026-10-01）

> 目标环境：`http://101.35.239.218:8088/`（腾讯云 · 直连部署 · 与艺培通同机）
> 目的：把 2026-09-30 14:18 之后累积的全部改动（含第五十二~六十批 9 个批次）部署到测试环境，
> 并用黑盒回归套件 + HTTP 探针做**真实运行链路**验证（CI 双绿只证明单测层）。
> 结论：**部署成功；回归 4 套件中 3 套 PASS、含 AI 全链路 PASS；并实测抓到 1 个真实缺陷（见 §4）**。

## 1. 部署前状态（侦察）

| 项 | 值 |
| --- | --- |
| 部署产物时间 | `app/api/*.jar` = **Sep 30 14:18**（此后全部改动未部署） |
| 服务 | nginx / mysql / intelligent-resume-api / pdf 全部 active |
| 资源 | 内存 3718MiB 总 / 1706MiB available；磁盘 39G（26G 可用） |
| 回滚点 | 部署前手工 `cp -a` 出 `app/api/rollback-20261001-193901.jar`（**脚本不备份 jar**；web / pdf-service 由脚本自动建 `.bak`） |

## 2. 部署（2026-10-01 19:39:10 → 19:41:40，2 分 30 秒）

`bash scripts/deploy-direct.sh`，7 步全部通过。关键证据：

- **Chromium 真实自检**：`Chrome/150.0.7871.24 | PDF 8285 字节`（渲染链路真实可用）
- PDF service 自报 `capabilities: ["pdf-render","7-resume-templates","ordered-resume-sections"]`
- 4 服务 active；API readiness UP；`nginx:8088 -> API` 200；公网入口自检 200

## 3. 验证

### 3.1 HTTP 探针（本机 → 公网 8088）

| 探针 | 结果 |
| --- | --- |
| 首页安全响应头 | `X-Content-Type-Options: nosniff`、`X-Frame-Options: DENY`、`Referrer-Policy`、`CSP-Report-Only` 四条齐 |
| 匿名 `/api/system/health` | 只回 `{service,status}`（无 `checks`）—— 收敛正确 |
| `/api/system/health/detail` 未认证 | **401** |
| API JSON 压缩 | `Content-Encoding: gzip` + `Vary: …accept-encoding`（应用层压缩生效） |
| 哈希静态资源 | `Cache-Control: max-age=2592000` + `public, immutable` |
| 7MB 上传 | **413** |
| 未认证访问导出任务 | **401** |

### 3.2 黑盒回归（`functional-tests/run_all.py http://101.35.239.218:8088`）

第一轮（首次部署后，含 `FUNCTIONAL_AI_LIVE=true`）：

| 套件 | 结果 |
| --- | --- |
| 基础链路 | **FAIL**（28 项中 1 项失败，见 §4） |
| 全功能扩展 | PASS |
| 安全与边界 | PASS |
| **AI 全链路**（7 类 AI 任务 + 授权撤回门禁） | **PASS** |

`suite_core` 明细：7/7 模板 PDF 导出全部 `PDF头=True 含中文字体=True`、各 ≈2s；跨账号隔离 3 项 404；
边界码 401/404/400 均正确；**唯一失败** `oversized-upload-413`。

**第二轮（nginx 配置修复后重跑，同样含 `FUNCTIONAL_AI_LIVE=true`）—— 4 套件全 PASS，0 失败、0 跳过**：

| 套件 | 第一轮 | 第二轮 |
| --- | --- | --- |
| 基础链路 | **FAIL**（27/28） | **PASS** |
| 全功能扩展 | PASS | **PASS** |
| 安全与边界 | PASS | **PASS** |
| AI 全链路（7 类任务 + 授权撤回门禁） | PASS | **PASS** |

> 第二轮是「修复后重验」的硬证据：既确认了 413 断言转绿，也确认了 nginx 配置变更未对 AI 链路
> 产生任何连带影响（此前只靠推理判断"不受影响"，现已有实测）。

## 4. 实测抓到的缺陷：nginx 站点配置从未随部署同步

### 现象

`oversized-upload-413` 断言 `code == 413 and body["code"] == 40001`（要求应用**统一信封**），
实际返回 **nginx 自带 HTML 错误页**：`<html><head><title>413 Request Entity Too Large</title>`。

### 取证（三条独立证据）

1. **服务器生效配置**：`/etc/nginx/sites-available/intelligent-resume` 为 `client_max_body_size 5m;`、
   **无 gzip 块**；文件时间 **Sep 25 15:46**
2. **仓库配置**：`deploy/nginx/host.conf` 已是 `client_max_body_size 6m;` + `gzip on / gzip_vary /
   gzip_min_length / gzip_comp_level / gzip_types`（含 js/css）
3. **打包范围**：`scripts/deploy-direct.sh` 的 tar 只含 `server web pdf-service test-fixtures`，**不含 `deploy/`**

### 影响（两条，均为已发生的实际失效）

- **第二十九批**（`client_max_body_size` 5m→6m）从未生效 → 5.5MB 上传被 nginx 拦在应用之前，
  用户拿到 HTML 而非接口文档承诺的统一信封
- **第三十五批**（静态资源 gzip）从未生效 → 修复前 `curl -I -H 'Accept-Encoding: gzip' /assets/*.js`
  **无** `Content-Encoding`，即该批声称的「1001KB → 285KB（3.5×）」收益实际为 **0**

> 这条缺口在第五十九批的文档对账中已被识别（`docs/DEPLOYMENT_DIRECT.md` §5 未说明 nginx 配置不在脚本内），
> 当时因「部署脚本改动无法在本机验证」而**只登记未修**。本轮云端验证把它从「理论风险」升级为
> 「有实测后果的缺陷」，并具备了修复后立即验证的条件。

### 修复

1. `scripts/deploy-direct.sh`：打包与解压范围加入 `deploy`
2. `scripts/deploy-direct.remote.sh`：在「同步产物」步骤内新增 nginx 配置同步（幂等）——
   先落位 `security-headers.conf` 片段（`host.conf` 的 include 依赖它，缺了 `nginx -t` 必失败）→
   覆盖站点文件 → 建 `sites-enabled` 软链 → **`nginx -t` 校验通过才 reload**（校验不过则中止且不 reload，
   正在运行的 nginx 不受影响）

### 复验（重新部署 2026-10-01 19:54:38 → 19:56:06，1 分 28 秒）

| 项 | 修复前 | 修复后 |
| --- | --- | --- |
| 服务器 `client_max_body_size` | `5m` | **`6m`**（文件时间 Oct 1 19:55） |
| 服务器 gzip 块 | 无 | **有**（`gzip on` + `gzip_types` 含 js/css） |
| 静态资源 `Content-Encoding` | **无** | **gzip** + `Vary: Accept-Encoding` |
| 5.5MB 上传 | nginx HTML 413 | 401（未登录，即**已穿过 nginx 到达应用**） |
| `suite_core` | 27/28 | **28/28**（`oversized-upload-413` PASS：`HTTP 413 {"code": 40001, "message": "上传文件超出大小限制"}`） |
| 完整回归（不含 AI） | — | **3 套件全 PASS，0 失败** |

## 5. 结论与残留

- **部署成功**：9 批改动已在真实环境运行；**AI 全链路（7 类任务 + 授权撤回门禁）真实通过**
- **修复并复验**了「nginx 配置不随部署同步」这一已发生的实际失效（两条独立影响均消除）
- **残留**：
  1. ~~AI 套件在 nginx 修复后未重跑~~ —— **已补齐**：nginx 修复后的**第二轮完整回归**（含
     `FUNCTIONAL_AI_LIVE=true`）**4 套件全 PASS、0 失败、0 跳过**（见 §3.2）
  2. `deploy/nginx/web.conf`（容器版）与 `edge.conf` 未纳入同步（容器拓扑未在生产使用，且 `edge` 端口由
     同机另一项目占用）；当前只同步宿主机直连版 `host.conf`
  3. 回滚点 `app/api/rollback-20261001-193901.jar` 保留在服务器上，确认稳定后可删

## 6. 后续：探针已固化为部署流程的一部分（第六十二批）

本轮排查用的探针当时是**手工**逐条执行的（curl / ssh），下次部署很容易被跳过 —— 那两条失效就会再次潜伏。
已在第六十二批固化为 `scripts/probe-deployment.sh`，作为 `deploy-direct.sh` 的**最后一步**自动执行；
探针失败时部署脚本以非零退出码结束，避免「部署成功」的假阳性。

- 用法与断言清单：`docs/DEPLOYMENT_DIRECT.md` §5.2
- 批次记录与红判定证据：`docs/reviews/2026-09-30-ideation-closure-audit.md` §2.54

---

## 7. 第三轮：第六十四 / 六十五批的云端复验（2026-10-01 23:12 → 23:35）

### 7.1 为什么要跑
第六十四批新增了一个 `@Scheduled` 作业与 4 个 `app.retention.purge.*` 数值键（接入 fail-closed 的
`NumericConfigurationValidator`，受校验键 14 → 18）。这两类改动正是「仓库自洽 ≠ 真实环境生效」的
高发区：**校验失败会让应用直接起不来**，而单测看不见真实的 `Environment`。

### 7.2 部署前状态

| 项 | 值 |
| --- | --- |
| 回滚点 | `app/api/intelligent-resume-server-0.1.0-SNAPSHOT.rollback-20261001-231226.jar` |
| 部署前 jar | 2026-10-01 20:35（第六十一批产物） |
| 服务器 `.env` | **无** `RETENTION_*` 键（走 `application.yml` 默认值，符合预期） |
| 服务 | nginx / mysql / api / pdf 全部 `active` |

### 7.3 第一轮部署（23:13，1 分 44 秒）—— 新配置与作业的生效证据

- 探针 **12/12 PASS**（当时探针仍是 12 项）
- `journalctl` 抓到作业在真实环境按默认执行：
  `Retention purge disabled (app.retention.purge.enabled=false); skipped`（线程 `worker-sched-1`）
  —— 证明**作业已装载**且**默认不删数据**
- 启动日志无「数值型配置未解析」报错 → 4 个新键在真实 `Environment` 解析成功
  （否则 `NumericConfigurationValidator` 的 `@PostConstruct` 会拒绝启动）
- `Started IntelligentResumeApplication in 11.911 seconds`，无 OOM

### 7.4 第二轮实测抓到的缺陷：**systemd 单元从未随部署同步**（第六十五批）

`ss -lntp` 显示 pdf-service 监听 **`*:3001`**，而非文档声称的 `127.0.0.1:3001`。

**取证（三条独立证据）**：

| # | 证据 |
| --- | --- |
| 1 | 服务器 `/etc/systemd/system/intelligent-resume-pdf.service` mtime = **2026-09-25 15:46**，`grep` 不到 `PDF_SERVICE_HOST` |
| 2 | 仓库 `deploy/systemd/intelligent-resume-pdf.service:25` **早已有** `Environment=PDF_SERVICE_HOST=127.0.0.1` |
| 3 | `scripts/deploy-direct.remote.sh` 对 `systemd` / `daemon-reload` **零命中** —— 它只同步 nginx |

**结论**：**第四十批的「PDF 服务收敛到回环」从未在真实环境生效**，与第六十一批的 nginx 洞
**同源** —— `deploy/` 里除了 nginx，**systemd 也只存在于部署手册的手工流程**里。
该暴露面只剩云安全组兜底；而安全组是云侧规则、在服务器内部**看不见**，所以必须
**在服务器上**直接看监听地址 —— 从外部探测会因安全组而假阳性。

### 7.5 修复

- `scripts/deploy-direct.remote.sh`：新增 **systemd 单元幂等同步**（内容变化才 `install` +
  `daemon-reload`；安装前剥离 CR，防工作区 CRLF 落进单元导致 systemd 解析异常）
- `scripts/probe-deployment.sh`：**12 → 16 项**
  - 第 6 节（B 类）：生效 systemd 单元 vs 仓库 `deploy/systemd/*.service`，归一化后逐行比对
  - 第 7 节（A 类）：**在服务器上**断言 API `8080` / PDF `3001` 的监听地址**仅绑回环**
- 同批修掉探针的一处小缺陷：`normalize` 补充去 `\r`（防工作区 CRLF 造成"整文件都不同"的假象），
  并提升到公共区供新小节复用

### 7.6 复验（红 → 绿）

| 轮次 | 状态 | 探针结果 |
| --- | --- | --- |
| **修复前**（新断言写完立即跑） | 服务器仍是旧单元、pdf 监听 `*:3001` | **14/16，exit 1**：第 6 节**精确指出**差异 `> Environment=PDF_SERVICE_HOST=127.0.0.1`；第 7 节 `PDF 3001 监听在非回环地址：*:3001` |
| **修复后**（23:17 → 23:19，1 分 47 秒） | 单元已同步 + pdf 重启 | **16/16 PASS**，`DEPLOY_EXIT=0`；`ss -lntp` = `127.0.0.1:3001` |
| 幂等复核 | api 单元内容未变 | **未被重写**（mtime 仍为 2026-09-25）—— 证明"仅在变化时安装"生效，不会每次部署都 `daemon-reload` |

### 7.7 黑盒回归（第三轮完整回归，含 AI）

```bash
env -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY FUNCTIONAL_AI_LIVE=true \
  python3 functional-tests/run_all.py http://101.35.239.218:8088
```

→ **4 套件全 PASS，0 失败、0 跳过**（基础链路 / 全功能扩展 / 安全与边界 / AI 全链路），耗时 15 分 28 秒。

### 7.8 结论与残留

- **部署成功**：第六十四批的作业与配置在真实环境生效；第六十五批修复了 systemd 链路洞并复验 16/16
- **残留**：
  1. `deploy/nginx/web.conf` / `edge.conf`（容器版）与容器路径的 systemd 不在同步范围（容器拓扑未在生产使用）
  2. 回滚点 `…rollback-20261001-231226.jar` 保留在服务器，确认稳定后可删
  3. 数据保留清扫作业**默认关闭**，本轮只验证了「作业装载 + 默认不删」；**真正的删除行为**需阶段 2
     口径确认后，在测试环境显式开启并单独演练（dry-run 一个周期 → 再真实删除）

