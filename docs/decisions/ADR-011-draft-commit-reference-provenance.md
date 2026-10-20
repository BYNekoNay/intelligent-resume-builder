# ADR-011: 草稿确认落库的材料引用只记录「真实被使用」的关系

## Status: Accepted (2026-10-20)

## Background

生成 → 确认 → 落库（`DraftCommitService.commit`）链路上，草稿每个节点带一条用户决策：`ACCEPT`（接受 AI 草稿）/ `EDIT`（改写后接受）/ `REJECT`（拒绝）。落库时还要向 `resume_material_reference` 写「这份简历版本用了哪些职业资料」的引用行，用于内容来源追溯。

原实现有两个与最终内容不符的口径：

1. **EDIT 仍引用 AI 原始素材**：用户改写后的事实被归因到 AI 素材，引用关系与最终简历内容不一致。
2. **REJECT 也写引用**：被拒绝的内容从未进入最终简历，却留下「被使用」的记录 → 引用表膨胀，引用关系因此不能作为内容来源的证据。

关键前提：`resume_material_reference` **不只是溯源表，它同时是留存分档的唯一判据**（`RetentionPurgeRepository`）——

- `career_material` 若**无任何引用** → A 档：软删恢复期结束后**物理删除**（SQL `:49-54`，判据在 `:52` 的 `NOT EXISTS`）；
- 若**有引用** → B 档：转**最小快照**保留（SQL `:78-82`，判据在 `:81` 的 `EXISTS`）。

因此「改引用口径」等价于「改留存承诺的实际覆盖范围」（`docs/04` §7.1）。这是本 ADR 存在的理由，也是这次改动不能当作一次顺手重构带过的理由。

## Decision

1. **EDIT 写单条 `USER_EDITED` 引用，指向新建的用户编辑资料**（`DraftCommitService:128-142` 建资料、`:215-220` 写引用）；新资料的 `contentJson` 先经 `stripProvenance`（`:319-345`）剥离 `_source` / `_sources` / `_pending`。→ **有意为之**：编辑后的事实不再声称来自 AI 素材；引用目标必须是「用户确认的最终内容」，而不是「AI 当时参考的素材」。
2. **REJECT 不写引用**（决策非 `ACCEPT` 的 selected 条目在 `:202-204` 被跳过）。→ **有意为之**：被拒内容未进入最终简历，「未被使用」不应留下使用痕迹。
3. **未列入 `items` 的 selected 素材：静默不写引用**（`decisionForSourcePath` 返回 `null` 时 `continue`，`DraftCommitService:202-204`）。→ **有意为之（语义：未确认 ≠ 被使用），但当前是静默行为，且未被测试钉死**：`commit` 只校验 `items` 的路径是否存在于草稿（`:111-117`），**不校验覆盖完整性**，故「部分确认」可达且既不会报错也不会写引用，调用方得不到任何信号。本 ADR 落盘时，`DraftCommitServiceTest` 的 8 个 `@Test` 只覆盖 `ACCEPT`/`EDIT`/`REJECT` 三条分支、**无 `decision == null` 用例**，即该语义当时只是「解释」而非「被断言保护的行为」，任何人改写该分支都不会有测试变红。**该语义由 `DraftCommitServiceTest.commit_selectedMaterialMissingFromItems_isSilentlySkipped` 钉住（归属 `feat(ai)` 提交）**：断言恰好 `times(1)` 一条引用、且指向被 `ACCEPT` 的那条 —— 刻意不用 `never()` 这类弱断言，因为弱断言在「全部跳过」的变异下仍会绿，证明不了跳过是**选择性**的。「是否应改为显式 `400/409`」仍登记为 OPEN（`OPEN-DECISIONS.md` 2026-10-20 条目），本批不实现；上述用例即 OPEN 关闭前的基线（若未来改成 400/409，它应当是唯一变红的地方）。
4. **历史引用行不回填**。→ **有意为之**：旧版本保留旧形状（EDIT 指向原始素材、REJECT 也有引用），新旧两种形状长期共存；任何按引用表做的统计或分档必须按 `selection_reason` + 时间窗区分口径。
   - `selection_reason` 现值域（`V8__resume_material_reference_reason.sql:2` 建列，`VARCHAR(500) NULL`）：`AI_REFERENCED`（`JobGenerationService:452` 写入）、`USER_EDITED`（本决策新增）。全仓**无任何读取方**（仅写入 + 测试断言），故两种口径共存不会立即破坏运行时，但取值域尚未在 `docs/04` 与前端联合类型中定义——已登记为 OPEN。

## Consequences

**正面**

- 引用表只保留「最终内容确实取自该资料」的关系，可作为内容来源证据使用。
- 用户编辑后的事实不再被错误归因到 AI 素材。
- 新建资料的 `contentJson` 不再夹带生成期内部键（`_source`/`_sources`/`_pending`），与 ADR-003 的口径一致：溯源标记属生成期中间产物，不属于用户资料内容。

**负面（必须写明）**

- **留存覆盖范围实质收缩，且不可逆**：原本因被 EDIT/REJECT 引用而进入 B 档（转最小快照保留）的 `career_material`，现在会提前落入 A 档，在软删恢复期结束后被**物理删除**。方向符合数据最小化（少留 PII），但删掉的行**无法通过回滚代码恢复**。这直接决定了本次改动必须与「投递 9 态 + 状态历史 + 导出复验」那批**分开提交**：否则那批的回滚会把一次不可逆的数据裁量一起带回来，而代码回滚救不回已删的资料。

**已知风险**

- `editedMaterialIds` 按 `outputPath` 建键（`:128` 声明、`:141` 写入、`:215` 遍历），两条 EDIT 若撞同一 `outputPath` 会互相覆盖，最终只剩一条引用；`ConfirmedDraftItem` 无 `outputPath` 唯一性校验，当前无兜底。

**可观测性缺口**

- EDIT 之后**无法回溯**「这条资料编辑前来自哪几条 AI 素材」：`stripProvenance` 主动剥离溯源键，且只写一条 `USER_EDITED` 引用。若将来需要，必须在 EDIT 落库时另行持久化「编辑前来源」，不能指望从现有数据反推。

**硬约束（由本 ADR 的「负面」推出，2026-10-20 追加）**

- 任何改变 `resume_material_reference` **写入条件**的改动，必须有承重测试覆盖 **ACCEPT / EDIT / REJECT / 未覆盖（`decision == null`）** 四条分支。理由：该表的写入条件同时是 A/B 留存分档判据，写错不是「少一行数据」，而是 `career_material` 被**物理删除**（见「负面」），且不可回滚。
- 实证（2026-10-20）：把 `DraftCommitService:202` 的条件临时改为 `|| true`（等价于全部 `continue`）后，`DraftCommitServiceTest` **9 个用例中 3 个变红**，还原后 9/9 绿 —— 四条分支当前均有承重用例。变异实验是这条硬约束唯一可信的验证形式：只跑绿不能证明用例在保护什么。

## Related ADRs

- **ADR-003**（溯源标记互斥只适用于要求溯源的节点）：本 ADR 是它在「用户编辑后内容」上的延伸——编辑产物不再携带生成期溯源。
- `docs/04` §7.1（数据生命周期基线）：A/B 两档的承诺文本；本 ADR 改变其**实际覆盖范围**，不改承诺文本。
- `server/src/main/java/com/intelligentresume/retention/RetentionPurgeRepository.java:49-54`、`:78-82`：分档判据（引用表极性反转）。
- `server/src/main/java/com/intelligentresume/ai/confirmation/service/DraftCommitService.java:128-142`、`:202-204`、`:215-220`、`:319-345`：引用写入路径。
- 守护测试：`DraftCommitServiceTest.commit_referencesOnlyAcceptedAndEditedFinalContent`（覆盖 ACCEPT/EDIT/REJECT 三条分支）；`DraftCommitServiceTest.commit_selectedMaterialMissingFromItems_isSilentlySkipped`（`decision == null` 的静默跳过分支，见 Decision 第 3 条 —— 该用例已落地，承重性由变异实验验证：注入 `|| true` → 3 红、还原 → 9/9 绿）。
