# monitoring/ 目录说明：两条部署路线，谁消费哪些文件

本目录混合了**容器路线**与**直连路线**的资产，取舍曾散落在各手册里，这里统一登记。
判断一个文件"活没活"，看它被哪条路线的入口引用，而不是看它存在与否。

## 两条路线

| | 容器路线（`monitoring/` 下的 compose 栈） | 直连路线（`deploy/` + systemd，**当前生产口径**） |
| --- | --- | --- |
| Prometheus 配置 | `monitoring/prometheus/prometheus.yml`（targets 为容器服务名 `api:8080`） | `deploy/prometheus/prometheus.yml`（targets 为 `127.0.0.1:8080`） |
| 告警规则 | `monitoring/prometheus/rules/*.yml` | **同一份**（部署脚本随部署同步到服务器同构路径，`rule_files` 指向它） |
| 告警出口 | Alertmanager（`monitoring/alertmanager/`，容器内 9093，按 severity 路由邮件/Webhook） | 轻量轮询：`deploy/check-alerts.sh` + `intelligent-resume-alertcheck.timer`（critical firing → 退出码 2 → journald / `systemctl --failed` / 探针第 11 节） |
| Grafana | `monitoring/grafana/`（Edge 反代 `/grafana/`） | 刻意不上（宿主机 3.6G 内存红线，见 `docs/DEPLOYMENT_DIRECT.md`） |
| 由谁守护 | `AlertRuleContractTest` 的 `availabilityAlertMatchesScrapeJobAndToleratesThreeFailures` 钉住 monitoring 份恰好 1 个 job | 同测试类的 `deployScrapeConfigAlsoLoadsRules` / `deployScriptSyncsRuleDirectory` 钉住 deploy 份加载规则、部署脚本同步目录 |

## 共享源（改动只改一处）

- **`monitoring/prometheus/rules/intelligent-resume-alerts.yml` 是唯一规则源**。
  容器路线由 compose 直接挂载；直连路线由 `scripts/deploy-direct.remote.sh` 从仓库同步。
  **禁止**在 `deploy/prometheus/` 下复制第二份规则——两份副本必然漂移，而漂移方向无法从门禁判断。
- `AlertRuleContractTest` 对两条路线各自独立断言（monitoring 份 1 个 job、deploy 份 2 个 job + rule_files），
  两份抓取配置结构不同，**断言不可互相复用**。

## 为什么直连路线不用 Alertmanager（2026-10-29 定案）

宿主机 3.6G、同机另一项目占 935MiB、swap 已吃 979M（`docs/DEPLOYMENT_DIRECT.md` 内存账）。
alertmanager 常驻 40~80MB + 独立投递通道配置，收益与代价不成比例；轮询出口最多延迟一个
timer 周期（5 分钟），对测试环境足够。若未来换正式环境（内存充裕或需要邮件升级），
再评估 alertmanager，届时 `deploy/check-alerts.sh` 的退出码语义可直接对接。
