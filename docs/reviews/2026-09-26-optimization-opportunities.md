# 优化机会盘点报告（2026-09-26）

> 性质：**只读优化机会盘点**（非缺陷核查）。基线 `codex/resume-loop-enhancements @ 919bc00`，后端 695 测试全绿、双远端已核验。
> 方法：四专家团并行（perf-arch / sec-hard / test-contract / obs-delivery）+ 项目总监独立交叉核验；每条带 文件:行号，区分【已核实】/【推断】。
> 输入：OPEN-DECISIONS 5 项 OPEN、模块核实报告 §3 暂缓 P2、2026-09-01 优化诊断 O 项（按磁盘代码逐项复核闭环状态）。

---

## 0. 先勘误：3 项登记与实况不符（总监逐一实证）

| # | 原登记 | 实况（证据） | 处置 |
| --- | --- | --- | --- |
| E-1 | 核实报告 §3「refresh token 进 JSON 响应体【T4】」 | **误报**。`TokenResponse.java:8` `@JsonIgnore String refreshToken` 自起步提交 `de480c2` 即存在（`git log -S` 唯一命中）；`AuthController.java:155-180` 经 HttpOnly + SameSite=Lax + Secure(可配) Cookie 下发；全仓仅 5 文件含 refreshToken，无其它响应体出口 | 本报告 §0 撤回该条；核实报告已加注 |
| E-2 | OPEN-DECISIONS ①「JVM 1G 上限」 | 实测 `deploy/systemd/intelligent-resume-api.service:40` = `-Xms128m -Xmx768m -XX:MaxMetaspaceSize=224m` | 登记册已就地修正为 768m |
| E-3 | 2026-09-01 诊断 O-03/O-08/O-10/O-11 状态 | **四项均已闭环**：`BailianAiProvider.java:441` 有「绝不记录响应体」注释且日志全结构化；InterviewHistoryService 原 N+1 调用形态已不存在；`POLL_TIMEOUT_MS` 全仓零残留；`monitoring/prometheus/rules` 已有 `AiProviderClientErrorsHigh`（4XX>10%）+ `AiProviderServerErrorsHigh`（5XX/TIMEOUT/CONNECTION>5%） | 不再列为优化项 |

---

## 1. 性能与架构（perf-arch + 总监核验）

| # | 优化项 | 现状证据 | 收益 | 成本 | 风险 | 优先级 |
| --- | --- | --- | --- | --- | --- | --- |
| PA-1 | **AI 任务 worker 分组领取**（OPEN①） | 【已核实】worker 每 tick 单条领取 + `FOR UPDATE ORDER BY id` 逐行排他锁，无 `SKIP LOCKED`（`AiTaskRepository:69` 注释写 SKIP LOCKED 实际没有，核实报告已登记注释漂移）；容量实况：JVM 768m（E-2）、Hikari 默认 10 连接 | 多用户场景短任务不再被长任务饿死（历史实测：内联润色 13s 被饿死 8 分钟） | 中：按任务类型分组领取 + 配置并发度 | 中：并发语义变化，需回归 695 测试 + 真实环境观察租约/心跳 | **P1** |
| PA-2 | JwtAuthFilter 每请求查库加短 TTL 缓存 | 【已核实】`JwtAuthenticationFilter.java:43-45` 每个带 Bearer 请求 `userRepository.findById` 查 ACTIVE | 每请求省一次 DB 往返；QPS 上升时收益放大 | 小：Caffeine 30–60s TTL | 低：DISABLED 用户生效延迟 ≤TTL（安全权衡需写进取舍说明） | **P2** |
| PA-3 | ScoringService 4 次抽取合一 | 【已核实】`ScoringService.java:98-101` 对同一简历连续 4 次独立遍历抽取（extract/extractRaw/extractSkillTokens/extractSkillRaw） | 毫秒级；个人量级不构成瓶颈 | 小 | 低 | **P3**（观察，可随 PA-1 顺带） |
| PA-4 | stats 改 SQL 聚合（O-07 未闭环） | 【已核实】`ApplicationService.java:132-144` 拉全量记录后 EnumMap 内存聚合 | 数据量增长后防退化 | 中 | 低 | **P3** |

## 2. 安全加固（sec-hard + 总监核验）

| # | 优化项 | 现状证据 | 方案 | 成本 | 优先级 |
| --- | --- | --- | --- | --- | --- |
| SH-1 | **actuator/prometheus 匿名暴露收敛** | 【已核实】`SecurityConfig.java:68-72`：`/api/system/health`、`/actuator/health/**`、`/actuator/info`、`/actuator/prometheus` 全部 permitAll，且测试机公网可达（101.35.239.218:8088）——prometheus 指标含 JVM/AI 链计数/请求量级等内部细节 | nginx 对 `/actuator/` 做 allow 内网/deny all（保留 health 路径放行），或 Spring Security 收敛至需认证；health 匿名只返回 UP/DOWN | 小 | **P1** |
| SH-2 | **imports parse 专项限流（CPU 放大器）** | 【已核实】`RateLimitFilter.java:78-84` `limitFor` 仅覆盖 login/register/refresh 三路径，parse 类重 CPU 端点不限流 | 扩展路径表 + 每用户维度（如 5 次/分） | 小 | **P1** |
| SH-3 | **nginx 安全响应头补齐** | 【已核实】`deploy/nginx/*.conf` 无任何 `X-Content-Type-Options`/`X-Frame-Options`/`Referrer-Policy`/CSP | 一处配置补 4 头（CSP 先 report-only 观察） | 小 | **P1** |
| SH-4 | 注册账号枚举 | 错误响应可区分用户名/邮箱已存在 | 统一「注册失败」文案，或文档化取舍 | 小 | **P2** |
| SH-5 | 改密后旧 access 短窗有效 | JWT 无状态固有窗口 | tokenVersion 字段（重） vs 文档化取舍（轻，推荐） | 小 | **P2** |
| SH-0 | ~~refresh token 响应体~~ | E-1 误报撤回，**无需任何改动** | — | — | 已闭环 |

## 3. 测试与契约（test-contract）

| # | 优化项 | 现状证据 | 优先级 |
| --- | --- | --- | --- |
| TC-1 | **follow-up 幂等重放 IT 零覆盖**（前端有调用方的 AI 触发端点） | server/src/test 无该端点重放断言 | **P1** |
| TC-2 | **i18n 门禁盲区**：`check-i18n.mjs` 仅扫 `.vue`，api/stores/composables `.ts` 不在审计范围（`:209-212`） | api 层硬编码中文 8+ 处绕过门禁 | **P1** |
| TC-3 | **错误码→前端文案映射整体缺失**（实测 16 处吞后端 message） | 前端无集中映射表 | **P1** |
| TC-4 | AtsFallbackCode 7 键无英文映射 | 前端映射缺口 | P2 |
| TC-5 | generic 白名单拒绝分支 IT（约 10 行） | 无覆盖 | P2 |
| TC-6 | taskUpdatedAt 乐观锁 40901 仅单测无 IT | 无覆盖 | P2 |
| TC-7 | answer AI 模式 HTTP 层重放 IT | 仅 service 层 | P2 |
| TC-8 | careermaterial 软校验：13 类仅 3 类有校验；最小 schema 候选表已产出（含消费方字段实测与历史数据风险） | 建议「软校验告警→观察→硬校验」三步走 | P2 |
| TC-9 | 契约文档化：generic 白名单契约进 `docs/05`；ADR-009（ai_task 幂等状态机）、ADR-010（interview attempt 生命周期）；`docs/05` §9.4 补 4 端点 | 文档缺失 | P2 |
| TC-10 | 简历并发保存 IT；e2e 3 小项 | 低频场景 | P3 |
| — | **已闭环不重复提报**：O-04 三项 e2e（CompareVersions/拖拽/报告交互均已覆盖）、5 个幂等端点 service 层 IT、ApplicationRecord `@Version` IT | 已复核 | — |

## 4. 可观测性与交付链（obs-delivery + 总监核验）

| # | 优化项 | 现状证据 | 优先级 |
| --- | --- | --- | --- |
| OD-1 | **CI 门禁与开发分支脱节** | 【已核实】`functional.yml:19` push 仅触发 `branches: [master]`；工作分支领先 master **116 提交**且从未合回（`merge-base = master`，master 独有 0 提交）——最近 116 个提交的功能门禁全部只在本地跑过。建议：① workflow push 触发加当前工作分支 ② 确立「master 定期 fast-forward 到工作分支」的基线策略 | **P1** |
| OD-2 | ai-live 门禁启用 | `functional.yml` ai-live job 已就绪；启用前置 = 配置 `BAILIAN_API_KEY` secret + dispatch 勾选；验收标准 = AI 全链路 31 项一次全绿 | **P2**（等用户配置密钥） |
| OD-3 | 日志告警出口 | 监控栈只盯指标不盯日志；错误日志无告警出口；格式非结构化 | P3 |
| OD-4 | OPEN 决策处置路线 | ① kimi-k3 移链：纯运维动作（改 `.env` + 重启，约 10 分钟）② 告警阈值：需一个配额周期生产数据 ③ K7 推理退化重验：需可信评分者 ④ ATS 600s 超时尾部：需产品口径 | 按阻塞条件分别推进 |
| — | **已闭环不重复提报**：O-11 告警细分（rules:11-20 已在）、O-03 隐私日志（E-3） | 已复核 | — |

---

## 5. 全局优先级与建议执行批次

**Top5（按 收益/成本 比 + 依赖关系排序）**：

1. **OD-1 分支与 CI 对齐**——约半小时、零代码风险，消除 116 提交的 CI 盲区（当前最大的结构性风险敞口）；
2. **SH-1/2/3 安全三小件打包**——actuator 收敛 + parse 限流 + 安全响应头，公网暴露面立减，改动面小；
3. **TC-1/2/3 测试契约三件套**——follow-up IT + i18n 门禁扩展到 `.ts` + 错误码文案映射；
4. **PA-1 worker 分组领取**——多用户价值最大，但需先做容量评估（768m 实测数据已就位），单独一个迭代；
5. **TC-8/9 + OD-2**——schema 三步走、契约 ADR 化、ai-live 启用，随批次顺带。

**不建议做（避免过度工程，沿用历史结论）**：ScoringService 重构（P3 观察即可）、ResumeEditorView 再拆分、引入 vitest、HomeView 组件化。

## 6. 变更记录

| 日期 | 变更 |
| --- | --- |
| 2026-09-26 | 初版：四团并行盘点 + 总监交叉核验；勘误 3 项（E-1 误报撤回 / E-2 JVM 768m / E-3 四项 O 已闭环）；产出 P1×6、P2×10、P3×4 |
| 2026-09-30 | **批次①（OD-1）+ 批次②（SH-0/SH-2/SH-3）完成并 CI 全绿收口**：双 workflow push 触发已含工作分支、master fast-forward 至 883abee；health 两级拆分 + parse 限流（6/15 每分）+ 安全响应头片段接入 4 份 nginx 配置，云端六项探针全 PASS，后端 704 测试 0 失败。CI 首跑 failure 双根因（suite_edges AI 断言无密钥门控 / CI 无 CJK 字体）已修复（505c0c8）并实证 Functional Regression success；顺带沉淀：套件断言应锚定语义而非环境特定实现（NotoSansCJK 单标记、AI 恒可用均为跨环境必炸写法） |
| 2026-09-30 | **批次④（PA-1）完成**：worker 由单线程串行改为「调度线程串行领取 + 分组线程池并发执行」。`AiTaskCapabilityRegistry` 增 `Group`（HEAVY/LIGHT）分组元数据（复用其「新增枚举未注册即 fail-closed」检查，避免分组表漂移）；`AiTaskRepository.claimableTasksByTypes` 按类型集合领取，并顺手修正原注释中不实的 SKIP LOCKED 描述（进程内领取始终单线程串行，重复领取由 `acquireLease` 条件更新兜底，无需 SKIP LOCKED，H2 亦不支持）；`DatabaseTaskWorker` 改为分组派发器，重/轻任务各持**独立线程池**与并发额度（`app.ai.worker.heavy-concurrency` / `light-concurrency`），故长任务占满重任务额度时轻任务不再被饿死。回归 708 测试 0 失败 |
| 2026-09-30 | **批次⑤（TC-8/9）完成文档与软校验部分**：TC-9 —— 补记 ADR-009（ai_task 幂等状态机：幂等域 `(user, taskType, key)` + 内容指纹 + 租约 + 重试上限，含「密钥无过期窗口/指纹对数组顺序敏感」负面后果）与 ADR-010（interview attempt 生命周期：两层唯一约束 + `pendingAnswer` 先落盘 + 两阶段短事务 + generation 陈旧丢弃 + `RULE_FALLBACK` 终态）；`docs/05` 补 §7.8 generic 白名单契约（6 类可走 `/api/ai/tasks`，未注册 fail-closed）、§9.4 由 3 端点补全为 9 端点（实测缺 6 个；并删除零代码依据的 `applicationRecordId` 漂移描述）。TC-8 —— 首次落盘 13 类校验矩阵与 10 类 contentJson 候选 schema（`docs/plans/2026-09-30-001-*`，含消费方字段实测与「成就引导/导入生成/自由 JSON」三类存量形态的硬校验风险）；实现软校验告警（`CareerMaterialSchema` 单点目录；半填形态记结构化 WARN 不拒绝），新增 31 用例，回归 **739 测试 0 失败**。OD-2 仍阻塞于 GitHub `BAILIAN_API_KEY` secret |

## 7. 批次执行状态（2026-09-30 更新）

| 批次 | 内容 | 状态 |
| --- | --- | --- |
| ① | OD-1 分支/CI 对齐 | ✅ **完成**（CI 全绿实证） |
| ② | SH-0 health 收敛 + SH-2 parse 限流 + SH-3 安全响应头 | ✅ **完成**（云端探针 + CI 全绿） |
| ③ | TC-1/2/3 测试契约三件套 | ✅ **完成**（89d07bb：TC-1 follow-up 幂等 IT 20 tests 绿；TC-2 门禁扫 .ts 实测 48 Vue+29 TS、修 3 处硬编码；TC-3 错误码映射表十码+resolveApiError+5 单测+3 处接入，web build 全绿） |
| ④ | PA-1 worker 分组领取 | ✅ **完成**（HEAVY/LIGHT 分组 + 各自独立线程池与并发额度；`DatabaseTaskWorkerTest` 新增分组隔离/额度回收断言，`DatabaseTaskWorkerIT` 改异步等待终态；回归 708 测试 0 失败） |
| ⑤ | TC-8/9 + OD-2（schema 三步走 + ADR + ai-live 启用） | 🟡 **部分完成**：TC-9 ✅（ADR-009 ai_task 幂等状态机 / ADR-010 interview attempt 生命周期 + 索引登记；`docs/05` 新增 §7.8 通用任务端点白名单契约、§9.4 由 3 端点补全为 9 端点并修正无代码依据的 `applicationRecordId` 描述）；TC-8 第一步 ✅（13 类校验矩阵 + 10 类候选 schema 落盘 `docs/plans/2026-09-30-001-*`；软校验告警上线，半填形态记 WARN 不拒绝；回归 739 测试 0 失败）；OD-2 ⏳ 仍阻塞于 GitHub `BAILIAN_API_KEY` secret |
