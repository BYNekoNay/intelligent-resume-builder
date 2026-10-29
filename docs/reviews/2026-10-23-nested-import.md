# 2026-10-23 批次 1.4：嵌套集合纳入预览 + 子记录不丢

> **性质**：修复记录（同批次的取证快照见 `docs/reviews/2026-10-23-export-import-roundtrip.md`）。
> **基线**：`master` = `53b3637`（批次 1.1 三个 P0 修复后），后端 1023 tests 全绿。
> **单一权威来源**：口径以 `docs/05` §18.1/§18.2 为准；实现以 `DesktopImportPlanner` /
> `DesktopImportWriter` / `ImportContentComparator` / `ImportValueMapper` 为准。
> **关闭的登记项**：`docs/decisions/OPEN-DECISIONS.md` 2026-10-21「导入预览不覆盖嵌套集合」→ RESOLVED。

## 1. 修的是什么（全部实测确认，不是推测）

| # | 缺陷 | 后果 |
|---|---|---|
| 1 | `preview()` 只遍历 4 个顶层集合，`children()` 唯一调用点在 `apply` 的 `insertChildren` | 版本/状态历史**从不参与分类**，内容变了既不判冲突也不提示 |
| 2 | `insertChildren` 只在**父记录判 ADD** 时调用 | 父 SKIP / CONFLICT 时**子记录根本不写** —— 数据丢失，比"未预览"更严重 |
| 3 | `idMap` 只在 ADD 播种 | 父 SKIP 时子记录取不到父键，`prepareValues` 保留 Web 主键 → FK 失败或**挂错父记录** |
| 4 | `updateRecord` 对子记录传 `parentRecord=null` | 子记录走 `USE_IMPORTED` 时父外键写回 Web 主键 |
| 5 | 外键重映射缺 3 处：`resume.job_description_id`(V12)、`resume_version.created_by`(FK→user)、`restored_from_version_id`(V11 自引用) | 真实**跨用户**包必然 FK 失败（既有用例 `createdBy=1` 恰好等于本地用户 id，所以从未暴露） |
| 6 | `sameContent` 不归一 JSON/时间/数字 | 真实包第二次导入满屏假冲突（`resume_json` 本地 byte[]、包内 Map；`changed_at` 本地 Timestamp、包内 ISO 串） |
| 7 | `REFERENCE_FIELDS` 缺 `applicationRecordId` / `restoredFromVersionId` | 子记录进比较后每条都判冲突 |
| 8 | 映射悬空（行被留存清扫删除）仍判 SKIP | 该 `stableId` **永久无法再导入** |
| 9 | `backfillCurrentVersion` 每插一个版本回填一次 | `current_version_id` 指向**最后插入的那条**，与包里 `currentVersionId` 无关 |
| 10 | `uk_resume_version_no (resume_id, version_no)` | 父 SKIP 且包里版本号撞本地已有号 → INSERT 失败、**整批回滚** |

## 2. 结构改动

`DesktopImportService` 实测 **553 行 / 棘轮基线 554**（只许下调），所以在同文件里加逻辑必红。
按「拆文件」处理，新增 4 个 package-private 类（都 <300 行，不进基线）：

| 文件 | 行数 | 职责 |
|---|---|---|
| `DesktopImportPlanner` | 242 | 一次遍历产出 `Plan`（扁平、父在前、子紧随父、每条带自己的决策）；含包校验、重复 stableId 拒绝、版本号占用预检 |
| `DesktopImportWriter` | 211 | 按计划顺序写入；**三种决策都播种 idMap**；映射 upsert；当前版本指针最后统一回填 |
| `ImportContentComparator` | 196 | 内容是否相同（JSON / 时间 / 数字归一）；嵌套字段**从注册表派生** |
| `ImportValueMapper` | 149 | 包字段 → 本地列值；全部外键改写规则集中在此 |

`DesktopImportService` 从 553 降到 **137 行** → 按棘轮规则**移出基线**（13 → 12 条）。
对外契约（`Decision` / `PreviewItem` / `ImportPreview` / `ImportResult` / 两个端点）**未变**，
只有 `PreviewItem.collection` 新增点分取值。

## 3. 关键判据（都已写进 `docs/05` §18.1/§18.2）

- **计数**：`added + skipped + conflicts == items.size()`，嵌套逐条计入；`items` 顺序 = 写入顺序（父先于子）。
- **父 SKIP/CONFLICT 时子记录照写**：子记录是独立条目，用自己的 `stableId` 决策。
- **版本号撞号 → 该条 SKIP**：不判 CONFLICT（会覆盖另一条记录）、不改号（破坏二次导入幂等，号会漂移）。
- **`created_by` → 本地用户**（桌面工作区只有一个用户，包内是 Web 用户 id）。
- **`restored_from_version_id`**：包内可解析则映射，否则置 NULL（保留必然 FK 失败，命中无关行则产生错误血缘）。
- **投递缺引用 → 整份拒绝**（列 NOT NULL，不写半份）。
- **状态历史幂等只靠映射**，不做自然键去重（属内容合并，违反 R37）。

## 4. 验证证据（全部来自当场命令输出）

| 验证 | 结果 |
|---|---|
| 新用例首跑（实现前） | **22 tests / 8 failures / 5 errors**，全部命中预期原因：`expected: <5> but was: <3>`（计数）、`预览里没有 web:resumes:606:versions:1606`（不下钻）、`keptLocal expected 1 but was 0`（子记录不可解决）、以及 4 条 `Referential integrity constraint violation`（外键缺口） |
| 实现后 | **22/22 绿** |
| 真实往返 IT | **2/2 绿**，含新增 `reimportingTheSameRealPackageReportsNoConflicts`（归一化的唯一承重点） |
| 契约门禁 | **6/6 绿**（新增 3 条：嵌套声明精确比对 / 子表与外键列存在于迁移 / 计划必须遍历 `children()` 且不得手写嵌套清单） |
| 全量 `mvn test` | **1039 tests / 0 failures / 7 skipped**（+16） |
| 棘轮 | 5/5 绿；基线 13 → 12（`DesktopImportService` 拆到 137 行后按规则移出） |

**门禁承重验证（5 组，改→跑→红→还原→核 md5）**：

| 注入 | 变红的断言 |
|---|---|
| 删掉 `versions` 子条目 | `childCollectionsMatchDeclaredNested`（2 条）+ `DesktopImportServiceIT` 15 条（计数/预览/写入级联） |
| 子表名改成 `resume_version_typo` | `childTablesAndForeignKeysExistInMigrations` |
| 外键列改成 `resume_idd` | `childTablesAndForeignKeysExistInMigrations` |
| 计划不遍历 `children()` | `importPlannerWalksRegistryChildren` |
| 在比较器里手写 `NESTED_FIELDS = Set.of(...)` | `importPlannerWalksRegistryChildren` |

还原后三份文件 md5 与注入前一致，`grep` 无注入残留。

## 5. 仍未做（不要当成已完成）

1. **未在真实环境手工验过**：验证止于 H2 file 测试库。发布前仍应做一次「测试环境导出账号包 → 桌面模式导入 → 逐项对比」。
2. **桌面端 UI 仍为零**（批次 1.6）：`web/src/api` 无 desktop 模块、路由无入口，用户只能通过 HTTP 直接调。
   *（2026-10-28 追记，不改上文：**已失效** —— 批次 1.6 已交付 `/desktop` 页面与 `web/src/api/desktop.ts`，见 `docs/RELEASES/v0.4.2.md` §一·4。）*
3. **`CONFLICT + USE_IMPORTED` 若把某版本改成已占用的 `version_no`** 仍会撞唯一键并整批回滚。
   判据：Web 侧版本不可变，实际不会触发；不为它加代码，已记入已知边界。
4. **状态历史不做自然键去重**：用户在本地手工补录过同一状态时，桌面会出现两条历史行（不是重复导入造成的）。
