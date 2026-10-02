# 数据生命周期 · 分档清扫方案（决策 D2 的实现设计）

> **决策来源**：`docs/decisions/DECISION-BRIEF.md` D2 —— 采 **B（分档承诺）**：
> 无引用资源按期硬删；被引用者转最小不可编辑快照 + 匿名化，`docs/04` §7.1 的承诺按此**分档表述**。
> **本文状态**：设计已出，实现**分三期**（见 §7）。**阶段 1 / 2 / 2b 均已实现**（第 64 / 68 批），
> 覆盖四个资源；仅**阶段 3（账户侧）**待定。
> **前置约束**：`RetentionPolicyContractTest` 已守护「无实现时不得移除 docs 里的『计划中』标注」——
> 因此**只有某期真正落地并可验证后**，才允许把对应承诺从「计划中」改为「已实施」。

---

## 1. 现状取证（2026-10-01，实测）

| 项 | 事实 |
| --- | --- |
| 软删列 | 仅 **5 张基础表**有 `deleted_at`：`user`、`career_material`、`resume`、`resume_version`、`job_description`（均在 `V1__m1_m2_init.sql`） |
| 清扫作业 | **不存在**。全仓 `@Scheduled` 中与清理相关的只有两处：`AiTaskRetentionService`（AI 任务留存）、`ExportExpiryService`（导出文件过期）——**均不处理软删资源** |
| 硬删仓储方法 | 无 `deleteByDeletedAt*` 之类方法 |
| 账户侧 | `AuthService.deleteAccount` 是**同步**路径（停用 + 撤销会话 + 取消 AI/PDF 任务 + 撤回同意），**不存在** docs 描述的「7 天撤销窗口」 |
| `account_deletion_job` 表 | `V1~V34` 中**不存在**（§7.2 的字段表是设计草案） |

### 1.1 引用面（决定「谁算被引用」）

`resume_version(id)` 被 **11 处**外键引用：

| 引用者.列 | 迁移 |
| --- | --- |
| `resume.current_version_id` | V1:100 |
| `resume_material_reference.resume_version_id` | V1:132 |
| `match_result.resume_version_id` | V1:151 |
| `ai_task.result_resume_version_id` | V1:194 |
| `export_task.resume_version_id` | V1:216 |
| `resume_version.restored_from_version_id`（**自引用**） | V11:9 |
| `inline_optimization_record.resume_version_id` | V3:15 |
| `ats_check_result.resume_version_id` | V4:12 |
| `application_record.resume_version_id` | V4:31 |
| `communication_draft.resume_version_id` | V17:12 |
| `interview_session.resume_version_id` | V7:15 |

另有 `career_material(id)` × **2**、`resume(id)` × **1**、`job_description(id)` × **8**。

> **结论**：直接硬删 `resume_version` 会撞 11 处外键 —— 这是「必须分档」的硬理由，不是偏好。

---

## 2. 口径定义

| 档 | 条件 | 处置 |
| --- | --- | --- |
| **A 档 · 无引用** | 软删行且**无任何**引用者（按 §3 判定） | 恢复期（30 天）满 → 宽限期（7 天）内**物理删除** |
| **B 档 · 被引用** | 软删行但**存在**引用者 | **转最小不可编辑快照**：保留主键与元数据列；清空/匿名化大字段与 PII；`deleted_at` 保留；**不**物理删除 |
| **C 档 · 账户** | `user` 的软删行 | **本期不处理**（涉账户与合规，见 §7 阶段 3） |

**恢复期与宽限期**对齐 `docs/04` §7.1 的既有承诺：`软删后 30 天为恢复期，期满后 7 天内完成硬删`。

---

## 3. 引用判定（每类资源一张「引用者清单」）

判定即：**是否存在任一引用者指向该行**。实现方式为**逐资源一条 EXISTS 查询**（不引入新的引用登记表，避免双写漂移）。

| 资源 | 引用者清单（任一命中即为 B 档） |
| --- | --- |
| `resume_version` | §1.1 的 11 处中**排除自引用**后的 10 处（自引用只在同一简历内成链，单独看待） |
| `career_material` | `resume_material_reference.material_id`、`interview_asset_section.material_id` |
| `resume` | `resume_version.resume_id`、`application_record.resume_version_id`→版本→简历（**间接**）、`export_task`→版本（间接） |
| `job_description` | 8 处引用（`resume.job_description_id`、`match_result`、`inline_optimization_record`、`ats_check_result`、`application_record`、`material_resume_generation`、`interview_session`、`communication_draft`）。⚠ 初版此处误列了 `ai_task` —— 该表**没有** `job_description_id` 列（第六十八批按迁移逐条核对 + 门禁复核后更正；门禁直接从迁移反查外键，故这类"文档多列/少列"不会影响实现） |

**间接引用**（`resume` / `job_description` 经 `resume_version` 传递）**保守处理**：只要存在**任一未删除的版本**指向它，即视为被引用（宁可保留，不误删）。

---

## 4. 处置矩阵（阶段 1 / 阶段 2）

| 资源 | 无引用（A 档） | 被引用（B 档） |
| --- | --- | --- |
| `resume_version` | 物理删除（**无引用即无子引用**，故可直接删） | 快照化：`resume_json` → `{"__purged":true}`（**常量**）；`generation_context`、`optimization_summary` 清空；保留 `id/resume_id/version_no/source_type/created_at/deleted_at`。⚠ 初版的「保留摘要前 200 字」**已否决**（非幂等 + 摘要本身可能含 PII），见下方注 |
| `career_material` | 物理删除 | 快照化：`content_json` → `{"__purged":true}`；**`source_text` 一并清空**（初版漏了这一 MEDIUMTEXT PII 载体）；`title` 保留。⚠ 初版的「只留键名」**已否决**，见下方注 |
| `resume` | 物理删除（**前提**：其下所有版本均已删） | **无 B 档**：只有 `title`，无大字段 ⇒ 被引用者**原样保留**（已实现，阶段 2b） |
| `job_description` | 物理删除 | 快照化：`jd_text` → **仅当超长时**截断到前 197 字符 + `...`（写成不动点以保幂等）；`parsed_keywords_json` 保留（非 PII）。已实现，阶段 2b |

> **注（第六十八批实施时对初版口径的修正）** —— 两处否决都来自实测，不是偏好：
>
> 1. **「保留摘要前 200 字」否决**：它要在同一条语句里从**即将被清空**的字段派生内容，第二次执行时
>    源已为空 ⇒ 结果不同（**非幂等**），而清扫作业会反复跑；且摘要是 AI 生成正文，本身可能就是 PII
>    载体。审计真正需要的是「这条版本存在过、属于谁、何时被删」，全在**保留的元数据列**里。
> 2. **「只留键名」否决**：需要把**动态生成的 JSON 文本**写回 JSON 列，而这一步在两个数据库上
>    **语义不同** —— MySQL 的 `CAST(? AS JSON)` 会把文本解析成 JSON 对象；H2 的
>    `CAST('{"a":1}' AS JSON)` 得到的却是 JSON **字符串值** `"{\"a\":1}"`（读回来带引号、不是对象），
>    要 `'...' FORMAT JSON` 才是对象。为一条审计辅助信息引入方言分支（生产 SQL ≠ 测试 SQL）不划算；
>    且键集本身可由 `material_type` 与资料契约推导。故统一为常量标记 `JSON_OBJECT('__purged', TRUE)`
>    —— 该函数两个数据库都有，无需绑定字符串参数。
> 3. **顺带修正一处 PII 遗漏**：`career_material.source_text`（MEDIUMTEXT，导入原文）初版没清 ——
>    它与 `content_json` 一样是 PII 载体，现已一并 `SET NULL`。

> **`user` 与账户**：见 §7 阶段 3（本方案不覆盖）。

---

## 5. 作业设计

```
@Scheduled(fixedDelayString = "${app.retention.purge.interval-ms:21600000}")   // 默认 6 小时
```

单次执行流程（**严格按序**）：

1. 读开关 `app.retention.purge.enabled`（**默认 false**）——关闭时立即返回并记一行 INFO。
2. 读 `app.retention.purge.batch-size`（默认 100）、`dry-run`（默认 **true**）。
3. 对每个受管资源表：查 `deleted_at <= now - (recovery-days + grace-days)` 的候选行（**LIMIT batch-size**）。
4. 逐行做引用判定（§3）→ A 档 or B 档。
5. `dry-run=true`：只记日志（含计划动作与计数），**不做任何写**。
   `dry-run=false`：按 §4 处置；每行一条审计日志（资源/主键/档位/动作/耗时）。
6. 本轮结束记汇总（扫描数 / A 档数 / B 档数 / 实际删除数）。

**幂等性**：快照化是「写入固定内容」，重复执行结果一致；物理删除本身幂等。作业可安全重跑。

**失败隔离**：单行处理异常 → 记 WARN 并继续（不中断整批）；本轮异常数计入汇总。

---

## 6. 护栏（强制，全部要有测试）

| # | 护栏 | 说明 |
| --- | --- | --- |
| G1 | **默认关闭** | `enabled` 默认 `false`；未显式开启时作业只记一行日志即返回 |
| G2 | **默认 dry-run** | `dry-run` 默认 `true`；首次上线必须先以 dry-run 跑满一个周期看日志 |
| G3 | **批量上限** | 单轮每表最多 `batch-size` 行，防一次扫全表 |
| G4 | **外键保护** | A 档删除前**再查一次**引用（TOCTOU 兜底），并捕获 `DataIntegrityViolationException` → 降级为 B 档重试一次，仍失败则跳过并告警 |
| G5 | **不碰账户** | 作业**不含** `user` 表（阶段 3 单独设计） |
| G6 | **可观测** | 日志 + 指标：`retention_purge_scanned/purged/skipped/snapshotted`（沿用既有 `Counter` 模式；计数为 0 时不注册序列） |
| G7 | **配置校验** | `recovery-days >= 0`、`grace-days >= 0`、`batch-size > 0`（接入既有 `NumericConfigurationValidator`） |

---

## 7. 分期实施

| 期 | 范围 | 状态 |
| --- | --- | --- |
| **阶段 1** | 作业骨架 + A 档**物理删除**（无引用者）+ 全部护栏（G1–G7）+ 可观测（G6） | **已实现**（第 64 批；开关默认关 + dry-run，落地时仅覆盖 `resume_version` / `career_material`） |
| **阶段 2** | B 档**快照化 + 匿名化**（§4 右侧列） | **已实现**（第六十八批，覆盖阶段 1 已纳入的两个资源；口径收敛为**常量快照**，「只留键名/保留摘要」经实测否决，见 §4 上方注） |
| **阶段 2b** | 把 `resume` / `job_description` 纳入同一范式 | **已实现**（第六十八批）。**「间接引用」不需要特殊处理**：曾担心「先引用版本、再由版本指向简历」会绕开直查，但 B 档只改内容、**不删行** ⇒ FK 始终成立，直查即可覆盖 |
| **阶段 3** | `user` / 账户侧（是否引入 7 天可撤销窗口 + `account_deletion_job`） | 需求本身待定（现为同步立即删除），**不建议现在做** |

> 阶段 1 / 2 / 2b 落地后，`docs/04` §7.1 的四个资源行已全部改为「已实施」；**仅账户侧**保留「计划中」，
> 由 `RetentionPolicyContractTest` 双向守护（已实施行不得再标计划中、账户行必须标）。

---

## 8. 测试计划

**实际落地形态**（第 64 批）：手写 SQL 的**主要风险是「漏判引用 → 误删被引用行」（不可逆）**，
因此守卫放在「真实 schema 上的元数据反查」而不是「造 FK 完整 fixture 的端到端用例」（后者成本高、
且只能覆盖到造出来的那几条路径）。

1. **`RetentionPurgeServiceTest`（Mockito，编排与护栏，6 例）**
   - G1 默认关闭 → 不查库、不删除、不记指标；
   - G2 开启但默认 dry-run → 只扫描不删除（并断言指标只记 scanned）；
   - A 档：无引用且超期 → 逐行删除并计入 `purged`；
   - G3：`batch-size=1` 时 `find` **只被调用一次**且带 `limit=1`（**一次调度只处理一批**，不循环耗尽）；
   - G4：删除时抛外键冲突 → 吞掉、不中断，计入 `skipped`；
   - 截止时间 = `now - (recoveryDays + graceDays)`。
2. **`RetentionPurgeRepositorySchemaTest`（`@SpringBootTest`，真实 schema，3 例）**
   - 候选 SQL 能在真实 schema 上执行（表名/列名与引用清单一致）；
   - **引用清单完整性**：从 `INFORMATION_SCHEMA.KEY_COLUMN_USAGE` 反查所有指向该资源的外键，
     与 SQL 里出现过的表逐一比对 —— **漏一项即红**（这是防「误删」的核心守卫）；
   - **删除语句二次保护**：两条 `DELETE` 必须恒带 `deleted_at IS NOT NULL`
     （候选查询被改坏时不误删活数据）。
3. **配置校验**：`NumericConfigurationValidatorTest` 按 `minimums()` 表自动覆盖新键
   （`batch-size` / `interval-ms` / `recovery-days` / `grace-days` 取「最小值 − 1」必须启动失败）；
   四个配置一致性门禁（`ConfigConsumerContractTest` / `ConfigFallbackContractTest` /
   `EnvExampleContractTest` / 本表）同步覆盖新键与样例。
4. **文档契约**：`RetentionPolicyContractTest` 按档位双向断言（A 档不得再标计划中、B 档与未覆盖资源必须标）。

**红判定（已做，实测）**：
- 从候选 SQL 删去 `ats_check_result` 一处 `NOT EXISTS` → `purgeSqlCoversEveryForeignKey` **红**
  （报出缺失表名）；
- 把 `RetentionPurgeProperties.dryRun` 默认值由 `true` 翻为 `false` → `dryRun_doesNotDelete` **红**；
- 两处均以 `md5` 校验完整恢复。

**残留（已登记）**：本阶段**没有**「造真实行 → 断言被真删」的端到端用例。代价是
「删除语句本身写错」只由 §2 第 3 条静态断言 + SQL 可执行性间接覆盖；若后续引入更完整的
fixture 工厂，可补一条 `@SpringBootTest` 行级用例。

---

## 9. 回滚

- **开关回滚**：`app.retention.purge.enabled=false` + 重启即可停止（无 schema 变更）。
- **数据回滚**：物理删除**不可逆** —— 因此阶段 1 先在**测试环境**跑，且必须满足 §6 G2（dry-run 一个周期）。
  生产启用前应另行确认备份策略（当前测试环境数据无保留价值，风险可接受）。
- **无迁移**：本方案**不新增迁移**（不建 `account_deletion_job`、不新增列），故无 schema 回滚需求。

---

## 10. 文档改写（阶段 1 落地后）

- `docs/04` §7.1/§7.2：把承诺改为**分档表述**（A 档已实施 / B 档计划中 / 账户侧计划中）；
- `docs/07` §5.10、`docs/08` §9.6：同步；
- `RetentionPolicyContractTest`：把「必须含『计划中』」的断言**按档位细分**（A 档不得再标计划中，B 档必须标）。
