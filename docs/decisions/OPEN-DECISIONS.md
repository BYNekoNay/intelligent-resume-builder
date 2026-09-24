# 悬而未决登记册（OPEN-DECISIONS）

> 铁律：**只追加 + 就地关闭**。OPEN → RESOLVED 时补 `Resolution` 字段，不删除原条目。
> 已关闭项可升格为 ADR。

| Date | Source | Open Item | Related Constraints | Current Leaning | Blocked By | Resolves When | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-09-23 | plans/003 (I2) | ⚠️ **严重度已降级**（ADR-005 使长任务从 477s 降到 ~7s，队首阻塞大幅缓解；串行本身仍在，多用户场景仍需修）<br>AI worker 按 id 串行领取，长任务（生成 477s）会饿死短任务（实测内联润色平时 13s 被饿死 8 分钟、`retryCount=0`） | `DatabaseTaskWorker` 每次 `claimBatch(owner,1)`；`claimableTasks` 为 `FOR UPDATE` + `ORDER BY id`；租约由 heartbeat 每 60s 续期 | 方案 A：按任务类型分组领取，短任务可插队；B（提高 worker 并发）待压测后定 | 需先确认容量预期（内存/DB 连接池/JVM 1G 上限） | 容量评估完成并选定 A/B 后 | OPEN · `waiting-on-external-condition` |
| 2026-09-23 | plans/003 (I3) | ~~生成任务每次在链首白等 300s 后顺延，总耗时 477s~~ | ~~`BAILIAN_READ_TIMEOUT_S=300`~~ | 诊断结论：**根因是推理开销**（88% 输出 token 为不可见 reasoning），非 prompt 规模亦非 provider 阻塞。a/b/c 三方案均不适用，改采方案 d（关闭推理） | — | ✅ 已关闭：线上实测 477s → **7s**，链首直接成功 | **RESOLVED** (2026-09-24)<br>Resolution: 对 `JOB_GENERATION` 关闭推理（`enable_thinking=false`），并配套把 400 改为顺延。见 ADR-005 |
| 2026-09-24 | ADR-005 §7.4 | `kimi-k3` 拒绝 `temperature` 参数，在本应用的任何请求下都必然 400，是链上纯失败跳转节点 | `BAILIAN_MODEL_CHAIN` 为环境变量；本应用的每次请求都带 `temperature` | 建议直接从链中移除（保留它只增加一次约 1s 的失败往返） | 需运维在部署机修改 `.env` 并重启 | 从 `BAILIAN_MODEL_CHAIN` 移除后 | OPEN · `waiting-on-external-condition` |
| 2026-09-23 | plans/003 (I4) | 模型链告警阈值 `available <= 2` 是否合适 | 默认链长 8，额度类冷却 30 分钟 | 取 2/8 ≈ 25% 余量作 early warning；实测告警触发频率后可能调整 | 需生产运行数据 | 观察一个配额周期（约 1 个月）后 | OPEN · `design-decision-to-evaluate` |
| 2026-09-23 | plans/003 §5 | 测试环境 `8.160.165.227` 的定位（现有 5 个用户全部为测试账号） | 直连部署（非 Docker）、无数据隔离 | 明确定位为**可随时重置的测试环境**并在部署手册声明（本次 S6 已补充声明） | — | ✅ 已关闭：见 DEPLOYMENT_DIRECT.md 环境属性说明 | **RESOLVED** (2026-09-23)<br>Resolution: 在部署手册中声明该环境属性与测试账号前缀，不再按准生产对待 |
| 2026-09-23 | plans/003 (I5) | 决策缺少可追溯登记册 | 此前决策只存在于 commit message 与评审文档 | 建立 `docs/decisions/` + ADR + 本登记册 | — | ✅ 已关闭：本目录建立并补录 ADR-001~004 | **RESOLVED** (2026-09-23)<br>Resolution: 新建 docs/decisions/，补录 4 条 ADR 并立本登记册 |
