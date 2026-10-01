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
  1. AI 套件在 nginx 修复后**未重跑** —— 判断依据是 nginx 配置变更只影响静态资源压缩、请求体积与安全头，
     不涉及 AI 链路；如需最强证据可再跑一次含 `FUNCTIONAL_AI_LIVE=true` 的完整轮次
  2. `deploy/nginx/web.conf`（容器版）与 `edge.conf` 未纳入同步（容器拓扑未在生产使用，且 `edge` 端口由
     同机另一项目占用）；当前只同步宿主机直连版 `host.conf`
  3. 回滚点 `app/api/rollback-20261001-193901.jar` 保留在服务器上，确认稳定后可删

## 6. 后续：探针已固化为部署流程的一部分（第六十二批）

本轮排查用的探针当时是**手工**逐条执行的（curl / ssh），下次部署很容易被跳过 —— 那两条失效就会再次潜伏。
已在第六十二批固化为 `scripts/probe-deployment.sh`，作为 `deploy-direct.sh` 的**最后一步**自动执行；
探针失败时部署脚本以非零退出码结束，避免「部署成功」的假阳性。

- 用法与断言清单：`docs/DEPLOYMENT_DIRECT.md` §5.2
- 批次记录与红判定证据：`docs/reviews/2026-09-30-ideation-closure-audit.md` §2.54

