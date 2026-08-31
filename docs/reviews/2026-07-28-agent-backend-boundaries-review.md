# 后端边界回归审查报告

## 审查范围

- 基线：`4b87dfc887f6402ef4b40bad47408a9e848e1375`
- 目标：分支 `codex/complete-resume-workflows` 的当前脏工作区。
- 纳入：`git diff 4b87dfc887f6402ef4b40bad47408a9e848e1375` 的 59 个跟踪文件，以及审查快照中 `git ls-files --others --exclude-standard` 返回的全部 65 个未跟踪文件。后者包括 54 个 `server/` 实现、迁移和测试文件，以及计划、结项报告和协作审查材料。
- 需求依据：`docs/plans/2026-07-27-002-remaining-audit-closure.md`，重点核对 R4-R8、U3-U6。
- 重点：正确性、认证和资源所有权、API 契约、Flyway V17/V18、后台租约、导出过期、导入边界、可靠性和性能。
- 限制：只读审查；未修改业务代码，未暂存、提交或推送。未把备份恢复、告警投递、容量验证或未执行的真实服务 E2E 视为已验证。

## 结论

当前后端不建议直接结项，判定为 **Not ready / Ready with fixes**。所有权隔离、InlineOptimize DTO、面试并发串行化和导出过期主路径已明显改善，完整 Spring 测试也为绿色；但 AI worker 的批量领取模型仍可在正常慢调用下造成队尾租约过期和重复外部执行，属于合并前应修复的 P1。V18 升级数据不变量和测试调度隔离为 P2。

- P0：无发现。
- P1：1 项。
- P2：2 项。
- P3：无发现。

## findings

### B-01 P1：批量领取的 AI 任务可在开始执行前失去租约

- 位置：`server/src/main/java/com/intelligentresume/ai/worker/DatabaseTaskWorker.java:37`
- 场景：生产默认一次领取 5 个任务，但随后在 `DatabaseTaskWorker.java:38-40` 串行执行。每个任务的心跳直到进入 `TaskExecutionService.execute()` 的 `TaskExecutionService.java:81` 才启动，而 5 个任务在 `TaskLeaseService.java:37-45` 被赋予同一个领取时刻计算出的到期时间。默认 AI 读取超时为 120 秒、租约为 180 秒；前两个慢调用即可让队尾任务等待超过租约。另一实例可重新领取该任务，原实例之后仍会调用 AI provider，只是在完成时被新的 owner 条件丢弃结果。
- 原文证据：`DatabaseTaskWorker.java:37 - List<AiTask> tasks = leaseService.claimBatch(owner, properties.getBatchSize());`
- 交叉证据：`DatabaseTaskWorker.java:38 - for (AiTask task : tasks) {`；`TaskExecutionService.java:81 - ScheduledFuture<?> heartbeat = startHeartbeat(task.getId(), owner);`；`application.yml:137-139 - lease-seconds: ...180 ... batch-size: ...5`。
- 影响：同一任务会产生重复外部 AI 调用、额外费用和重复 attempt 计数；等待造成的重新领取还可能提前耗尽重试/配额。owner 条件能阻止旧结果覆盖新结果，但不能阻止重复副作用。
- 建议修复：串行 worker 每次只在即将执行时领取 1 个任务；或者用有界执行池并在领取成功后立即为每个任务启动心跳。不要只提高租约时间，因为批量大小和调用时延仍可再次突破固定上限。
- 验证建议：增加双 worker 数据库集成测试，设置 `batch-size > 1`、短租约和可控慢 provider；断言队尾任务在等待期间不可被第二 worker 领取、每个任务只调用 provider 一次、attempt 只增加一次。
- 置信度：100。执行顺序、心跳启动点和默认时间参数均可由代码直接证明。

### B-02 P2：V18 未回填历史轮次，也未建立数据库级非空约束

- 位置：`server/src/main/resources/db/migration/V18__interview_record_round.sql:1`
- 场景：已有 `interview_record` 数据的数据库升级到 V18 时，新列全部为 `NULL`；唯一索引允许同一 session 存在多个 `NULL`。应用实体却在 `InterviewRecord.java:13` 声明 `nullable = false`，新逻辑把 `countBySessionId()` 推导出的值写入 `round_no`。升级库因此与领域不变量不一致，历史记录没有稳定轮次，数据库也无法阻止未来绕过 ORM 写入空轮次。
- 原文证据：`V18__interview_record_round.sql:1 - ALTER TABLE interview_record ADD COLUMN round_no INT NULL;`
- 交叉证据：`V18__interview_record_round.sql:3-4 - CREATE UNIQUE INDEX uq_interview_record_session_round ON interview_record(session_id, round_no);`；`InterviewRecord.java:13 - @Column(name = "round_no", nullable = false) private Integer roundNo;`。
- 影响：新建空库测试全部通过，但生产升级后的数据契约不同；依赖轮次排序、唯一性或数据修复的后续功能会面对无法区分的历史记录。存在 3 条历史记录但 session 仍为 `IN_PROGRESS` 时，`InterviewService.java:62-64` 试图改为完成后抛异常，事务回滚又会保留不一致状态。
- 建议修复：在 V18 中按每个 session 的 `created_at, id` 确定性回填 `ROW_NUMBER()`，校验重复和范围后将列改为 `NOT NULL`；同时明确修复已有 `IN_PROGRESS` 且达到最大轮次的 session 状态。MySQL 和 H2 若窗口函数/DDL 语法不同，应使用数据库兼容的分步迁移。
- 验证建议：增加真正的升级测试：先迁移到 V17，插入同一 session 的多条历史记录，再运行 V18，断言轮次为 `1..N`、列不可空、唯一约束生效且 session 状态符合策略。
- 置信度：100。迁移缺少回填/非空 DDL，实体约束与数据库约束不一致，可直接验证。

### B-03 P2：测试 profile 的调度 worker 会异步消费其他集成测试创建的任务

- 位置：`server/src/test/resources/application-test.yml:78`
- 场景：测试 profile 只把轮询间隔设为 60 秒，没有禁用 `@Scheduled` worker，也没有设置首次延迟。完整测试运行期间，`worker-sched-1` 实际领取了其他 IT 留在共享 H2 中的任务并将其执行为失败；Surefire 证据位于 `target/surefire-reports/TEST-com.intelligentresume.ai.task.repository.AiTaskRepositoryIT.xml:105-106`。当前空 API key 阻止真实 provider HTTP 调用，但后台状态变更仍与测试线程竞争。
- 原文证据：`application-test.yml:78 - poll-interval-ms: 60000`
- 交叉证据：`DatabaseTaskWorker.java:33 - @Scheduled(fixedDelayString = "${app.ai.worker.poll-interval-ms}")`；本次 `mvn test` 日志包含 `thread_name:"worker-sched-1"` 对 `JOB_MATERIAL_SELECTION` 任务的失败处理。
- 影响：测试结果依赖调度时序，任务状态、retryCount 和断言可能被后台线程改变；这与此前“AI 授权前置条件”类偶发失败相符，也让绿色结果难以稳定复现。
- 建议修复：为 AI/PDF worker 增加 `enabled` 条件，测试 profile 默认关闭；仅在 `DatabaseTaskWorkerIT` 等专门测试中显式开启，或直接调用 worker 并注入本地假 provider。不要依赖超长 poll interval 作为隔离机制。
- 验证建议：连续多次运行完整 Spring 套件，断言非 worker IT 的 Surefire 输出不再出现 `worker-sched-*` 任务执行日志；专门 worker IT 仍覆盖领取、失败和重试。
- 置信度：100。完整测试日志已经复现了跨测试后台消费。

## testing_gaps

- 没有 `batch-size > 1` 且 provider 延迟超过一个租约窗口的 AI worker 集成测试；现有测试 profile 固定 `batch-size: 1`，无法发现 B-01。
- 没有从 V17 带历史 `interview_record` 数据升级到 V18 的迁移测试；当前 Flyway/Spring 测试只证明空库可以应用 18 个迁移。
- `ExportExpiryService` 的删除失败在 Mockito 单测覆盖，但没有数据库提交失败、并发下载/清理和 scheduler 重启恢复的集成测试。
- `npm run test:e2e:local` 未在本轮执行，R4 的真实 Web/Spring/数据库/PDF 串联仍只能标为“代码已具备、环境证据缺失”。
- 本轮执行 `server: mvn test`：308 个测试通过，0 failure/error/skip；另有 38 个高风险聚焦测试通过。旧结项报告中的 298 和用户转述中的 27/30 均不是当前工作区的最终计数。
- `git diff --check 4b87dfc...` 通过，仅有 LF/CRLF 提示。

## residual_risks

- PDF worker 的既有 lease 完成路径没有 owner 条件更新或心跳；默认 15 秒渲染超时低于 90 秒租约，当前没有复现，但配置漂移或慢本地存储可再次触发 stale worker。因相关完成逻辑不在本次改动中，记为遗留风险而非本次 finding。
- 导出过期采用“先删文件、再在事务中清状态”。删除是幂等的，数据库失败后可由 scheduler 重试收敛，但仍需监控长期处于时间已过期却状态为 `SUCCESS` 的记录。
- 导入仅限制压缩文件本身为 5 MB；Apache POI/PDFBox 的解压和解析保护依赖库默认值，尚无压缩炸弹、超大页数或超大解压文本测试。
- 未发现 Application/ATS/Communication/Interview/InterviewAsset/InlineOptimize 的可复现跨用户读取或写入；资源查询均按 user 过滤或在服务层返回 404。该结论基于代码和 H2 IT，不替代生产鉴权日志审计。
- 备份恢复、告警实际投递、容量上限和真实服务浏览器 smoke 仍缺少环境证据。

## deployment_notes

- Go/No-Go：B-01 修复并通过双 worker 慢调用测试前，不建议以 `AI_WORKER_BATCH=5` 多实例部署。
- V18 上线前执行只读检查：`SELECT COUNT(*) FROM interview_record WHERE round_no IS NULL;`。升级后该值应为 0；若不是 0，不应宣称轮次不变量完成。
- V18 回填后验证：`SELECT session_id, round_no, COUNT(*) FROM interview_record GROUP BY session_id, round_no HAVING COUNT(*) > 1;` 应返回 0 行。
- V17 为新增表，需在目标 MySQL 验证三个外键和 `idx_communication_user_created` 已创建；回滚前确认没有需要保留的 draft 数据。
- 测试环境应默认禁用调度 worker；生产环境则需要监控 PENDING 最老年龄、RUNNING 过期租约、重复 attempt 和 stale completion 日志。
- 备份恢复、告警投递、容量验证必须继续标为 deferred，直到有真实环境记录。

---

## 最终判定

**Not ready。** 优先修复 B-01；随后完成 B-02 的迁移决策和升级测试，再处理 B-03 的测试隔离。P0/P3 无发现，其余未确认项已放入 testing_gaps 或 residual_risks，未作为缺陷夸大。
