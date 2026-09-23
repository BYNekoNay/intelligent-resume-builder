> ⚠️ **编码修复说明（2026-09-23）**
>
> 本文原件在首次也是唯一一次提交（`dd2e954`）时即为**双重编码损坏** —— 原始 UTF-8 字节被按 GBK 解码后又存成 UTF-8，
> 全文没有干净版本可回退。现已按 GBK 用户自定义区映射（`AAA1-AFFE`、`F8A1-FEFE`、`A140-A7A0`、`A840-A9A0` 及单字节 `0x80`）
> 反解修复，正文恢复可读。
>
> **少数原始字节在损坏阶段即已丢失，无法找回**，以 `�` 标出（集中在个别汉字的半个字）。原件字节完整保存在 git 提交 `dd2e954` 中。
>
> 另：本文属历史审查记录，其结论针对 V20 AI 状态机引入**之前**的三轮规则流程。当前结论与验证证据以
> [`2026-07-28-ai-interview-implementation-report.md`](./2026-07-28-ai-interview-implementation-report.md) 为准。

---

# 模拟面试流程独立审查（增量复审）

> 历史审查说明：本文审查的�?V20 AI 状态机引入前的三轮规则流程，测试数量、接口契约和发布结论不再代表当前候选实现。当前结论与验证证据�?[`2026-07-28-ai-interview-implementation-report.md`](./2026-07-28-ai-interview-implementation-report.md) 为准�?
日期�?026-07-28
结论�?*可以合并（Ready to merge），发布前需完成迁移清单核验**

## 审查范围

- 当前工作区中模拟面试相关�?10 个已跟踪改动，以及存在于工作区但按本轮要求未提交�?`V19__optional_interview_job.sql`�?- 后端：三轮会话状态机、并发串行化、无岗位启动与作答、评分和报告、实体及请求约束�?- 数据库：V18 历史轮次与完成态回填、MySQL 5.7 兼容性、V19 岗位外键可空、Flyway checksum 处置边界�?- 前端：可选岗位确认、第三轮完成态、报告完整字段、中�?英文文案�?- 测试：`InterviewControllerIT`、`FlywayMigrationIT` �?`web/e2e/workflow.spec.ts` 新增覆盖�?
## 复审证据

- `aaf57ea` 仅�@ `codex/complete-resume-workflows` �?`codex/frontend-design-refresh` 包含；`git merge-base --is-ancestor aaf57ea master` 返回非零，且 `master` 中不存在 V18 文件。V18 尚未形成主线或生产迁移契约�?- 已提交候�?V18 使用 `ROW_NUMBER()`，目�?MySQL 5.7 不支持窗口函数；当前自连接计数实现是合并前必要的兼容性修正�?- 唯一应用过中�?V18 的本地开发库已执行定�?Flyway repair，当�?V18 checksum �?`-1004219153`；生产和主分支未发布 V18。该处置不会把生产历史漂移合理化，只覆盖已盘点的本地中间状态�?- V18 �?25-33 行会把记录数不少�?3 且仍�?`IN_PROGRESS` 的旧会话回填�?`COMPLETED`；`FlywayMigrationIT:268-274` 同时断言三轮编号和持久化完成态�?- 无岗位评分在 `InterviewService:126` 以中性方式获�?JD 维度 10 分；`InterviewControllerIT:84-94` 使用完整 STAR、长度和量化回答，连续三轮断言 100 分及第三轮结束�?- 后端测试报告显示 `FlywayMigrationIT` 9 项、`InterviewControllerIT` 6 项，�?15 项全部通过，无失败或跳过�?- 前次验证的前端生产构建通过；新�?Playwright 用例 3/3 通过，覆盖无岗位确认、有岗位免确认、三轮完成态及完整报告�?- V19 文件当前未跟踪是“不提交代码”约束下的预期工作区状态；文件存在，H2 迁移测试通过，并已在真实 MySQL 环境应用验证列可空及外键保留。因此不再把工作区暂未跟踪本身判定为代码 finding�?
## Findings

### P0

�?P0 问题�?
### P1

�?P1 问题�?
�?#1（V18 checksum 阻断）关闭：其前提“V18 已进入主线或生产”不成立。V18 只存在于功能分支，且必须在合并前替换�?MySQL 5.7 不支持的窗口函数；唯一中间开发库已完成定�?repair�?
�?#2（V19 未跟踪）关闭：当前未跟踪状态源于本轮明确不提交的约束，不代表候选变更缺少迁移文件。V19 已存在并通过 H2 与真�?MySQL 验证。最终提�?交付时纳入文件属于发布完整性检查，而非当前实现缺陷�?
### P2

�?P2 问题�?
�?#3（通用面试最�?90 分）关闭：无岗位路径现在中性获�?JD 维度 10 分，高质量回答可�?100 分，�?Controller IT 已用三轮 100 分断言锁定该行为�?
### P3

�?P3 问题�?
## 状态机与界面结�?
- �?1�? 次回答持久化独立轮次并返回下一题；�?3 次回答在同一事务中写�?`round_no=3`、将会话置为 `COMPLETED` 并返�?`nextQuestion=null`�?- 会话行使用悲观锁串行化并发回答，轮次唯一索引提供数据库兜底；现有并发 IT 验证两次并发请求得到不同轮次和不同题目�?- �?4 次回答返回冲突，不会新增记录。历史上已有不少于三条记录但仍进行中的会话由 V18 修正为完成态�?- 无岗位的四种面试模式均有�?JD fallback 问题，不会解引用空岗位；反馈不会生成虚假的岗位匹配建议，评分量纲仍保�?100 分制�?- Vue �?`nextQuestion === null` 进入完成态，隐藏答案输入与提交按钮，保留最后一轮反馈和报告入口；E2E 已验证三轮切换和完整报告字段�?
## 测试盲点与残余风�?
- 自动化数据库测试使用 H2 MySQL mode。真�?MySQL 已手工验证本次迁移，但仓�?`docker-compose.yml` 和生�?compose 当前使用 MySQL 8.4，而目标兼容口径包�?MySQL 5.7；建议在 CI 增加 MySQL 5.7 迁移作业，防止后续再次引入窗口函数或版本专属 DDL�?- E2E 三轮用例 mock 后端，主要证�?Vue 状态转捨H��真实后端契约�?Controller IT 覆盖。若项目保留本地服务 E2E，可再增加一条真实前后端三轮闭环，但不阻断本次合并�?- 新会话第三轮后的数据库状态目前通过第四次冲突间接验证；V18 测试直接验证的是历史回填状态。可�?Controller IT 中注�?session repository 并直接断言新会话状态，属于增强覆盖而非当前缺陷�?- V18 的自连接回填复杂度高于窗口函数。每个会话预期记录数很小，当前风险有限；若真实历史表规模较大，上线前应在副本测量 V18 执行时长和锁影响�?- 本地开发库�?Flyway repair 是一次性例外。若发现任何其他环境应用过中�?V18，必须先核对�?SQL �?schema 实际状态，再决�?repair，不能批量照搬�?
## 迁移上线检�?
### 上线�?Go/No-Go

- 最终提交或交付包必须包�?V18 当前 MySQL 5.7 兼容版本�?V19；在干净检出中确认 Flyway 能发�?19 个迁移。当�?V19 未跟踪可接受，但发布时遗漏为 No-Go�?- 确认所有目标环境的 `flyway_schema_history`：生�?主线环境不应存在中间 V18；已知本地开发库�?V18 checksum 应为 `-1004219153`�?- 在目�?MySQL 5.7（或等价副本）从 V17 演练 V18 -> V19，检查轮次、状态、可空列及外键；真实 MySQL 已有一次验证，发布流水线仍应保留可重复证据�?- 上线前统�?`IN_PROGRESS` 且记录数不少�?3 的会话数量，核对 V18 状态回填的预期影响行数，并评估迁移执行时长�?- 在最终候选提交上重新运行 15 项聚焦后端测试、前端生产构建和 3 条面�?E2E�?
建议核验查询�?
```sql
SELECT version, description, checksum, success
FROM flyway_schema_history
WHERE version IN ('18', '19');

SELECT COUNT(*)
FROM interview_session s
WHERE s.status = 'IN_PROGRESS'
  AND (SELECT COUNT(*) FROM interview_record r WHERE r.session_id = s.id) >= 3;

SELECT is_nullable
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'interview_session'
  AND column_name = 'job_description_id';
```

### 上线后验证与回滚

- 验证�?无岗位均可启助L��无岗位四种模式连续作答三次后状态为 `COMPLETED`，第四次返回冲突，报告显示三轮平均分�?- 监控 Flyway validate 错误、迁移耗时、`job_description_id` 非空约束错误，以及启�?作答接口�?5xx�?09 比例�?- V19 是放宽约束，数据库层面可前向兼容；但应用回滚到旧版本后，新建的无岗位会话无法继续作答。回滚前应暂停无岗位入口，并处理 `job_description_id IS NULL` 的会话；不要在仍�?NULL 数据时直接恢�?`NOT NULL`�?- V18 状态回填是条件更新。应用回滚不应重新开放已经完成三轮的会话�?
## 最终结�?
增量复审后，原有 2 �?P1 �?1 �?P2 findings 均已由新证据或代码修复关闭，当前未发�?P0-P3 可操作缺陷。三轮状态机、可选岗位、通用评分、历史会话回填、Vue 完成态与 E2E 形成完整闭环�?5 项聚焦后端测试和既有前端验证均为绿色�?
结论调整�?**Ready to merge**。V19 当前未跟踪不阻断本轮只读审查，但最终提�?发布必须包含该文件；同时应按上线清单核对 V18 checksum、MySQL 5.7 迁移执行和历史会话影响。Actionable findings: none.
