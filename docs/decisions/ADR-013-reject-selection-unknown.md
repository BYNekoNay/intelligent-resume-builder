# ADR-013: 未列入 `items` 的 selected 素材改为显式 40904 拒绝

## Status: Accepted (2026-10-29)

## Background

`POST /api/ai/tasks/{id}/confirm`（生成草稿确认）的入参有两侧：

- **`selected`**：AI 生成时写进任务结果的素材溯源条目（`materialId` + `outputPath` + `selectedReason`），服务端产出、客户端不可改；
- **`items`**：用户在确认页对草稿每个节点的决策（`ACCEPT` / `EDIT` / `REJECT`），客户端提交。

`DraftCommitService` 落库引用时，对 `selected` 里的每个条目按 `outputPath` 在 `items` 中找覆盖它的决策（`decisionForSourcePath`，支持父路径前缀匹配）。找不到时的历史语义是**静默 `continue`**（ADR-011 Decision 3，语义「未确认 ≠ 被使用」）。

该语义有两个后果，都已实测确认：

1. **客户端少提交会变成"成功"**：`items` 只校验路径存在于草稿（`DraftCommitService` 第 6 步），**不校验覆盖完整性**。于是"用户勾了 5 条只提交 3 条"这类客户端 bug 会得到一个 200 —— 版本落库了、引用少了 2 条，用户与运维都收不到任何信号。
2. **没有回滚路径**：引用表同时是留存分档的唯一判据（`RetentionPurgeRepository`，见 ADR-011 背景）。少写的引用会让对应素材提前落入 A 档（物理删除），**代码回滚救不回已删的数据**。

前置调查（本 ADR 采纳前必须回答的问题）：**前端是否存在合法的"部分提交"路径？** 结论是**不存在**：

- `GenerationConfirmView.doConfirm` 提交 `items` 的方式是 `draftItems.value.map(...)` —— 全量映射，且未操作项默认 `ACCEPT`；
- `draftItems` 由 `useDraftReview.parseDraft` 从 `draftResumeJson` 的每个可审阅章节展开（数组按 `section[idx]`、对象按 `section`），而 `selected[].outputPath` 或等于某个节点路径、或是其子孙（`work[0].highlights[1]`），前缀匹配都能覆盖。

因此缺项只可能来自：非 UI 客户端、或新章节未纳入 `REVIEWABLE_SECTIONS` —— **两者都是需要报错的缺陷，不是合法流程**。

## Decision

1. **`selected` 中有、`items` 中无 → 抛 `40904 SELECTION_ITEM_UNKNOWN`**（`"选材已过期，请重新载入后确认"`），**在任何写库与读快照之前**失败（校验前移到第 6b 步，不依赖事务回滚撤掉已创建的版本）。
2. **`decision != ACCEPT` 仍是合法的静默跳过**（用户明确拒绝该项）。两者都"不写引用"，但前者是错误、后者是正常流程 —— 必须有成对用例钉住这个差分，否则回归时会把正常拒绝也判成错误或反之。
3. **码值取 `40904` 而不是 `40903`**：登记册（`OPEN-DECISIONS.md` 2026-10-20 条目，2026-10-28 复核）原写 `40903`，但该值已被 `ACCOUNT_DELETION_WINDOW_EXPIRED` 占用（前端 `errorCodes.ts` 亦已映射），沿用会让两套文案撞车。已同步更正登记册。
4. **`selection_reason` 的取值域同步封闭**（D5）：新增 `SelectionReason`（`AI_REFERENCED` / `USER_EDITED` 两值），写入前归一化，未知值抛 `40001` 而非静默落库 —— 溯源字段出现无法解释的值等于伪造证据。校验同样在写库之前执行。
5. **历史数据不回填**：旧版本里"少写了引用"的行无法还原（不知道当初漏了哪条），不做猜测性修复。

## Consequences

**正面**

- 客户端缺陷立刻可见（40904 + 明确文案），不再退化成"静默少几条溯源引用"。
- 与 `40901` 分码：`40901` 的处置是"刷新重试"，`40904` 的处置是"重新载入选材"，前端可给出准确指引。
- 引用表的完整性从"依赖客户端自觉"变为"服务端强校验"，留存分档的输入因此可信。

**负面**

- **API 行为变更（破坏性）**：此前能返回 200 的"部分提交"请求现在返回 40904。若有第三方客户端依赖旧行为，需要一次性适配（本项目暂无第三方调用方，前端已确认全量提交）。
- 确认页若将来新增可审阅章节而忘记同步 `REVIEWABLE_SECTIONS`，用户会在确认时看到 40904 —— 这是刻意让缺陷显性化，代价是文案要能指向"重新载入"。
- 多一处早失败点：`selected` 里 `materialId` 为空的条目仍按原逻辑跳过（本就不产生引用），不纳入校验范围。

## References

- 被本 ADR 取代：`ADR-011` Decision 3（原文不改写，以本 ADR 为准；ADR-011 文末已追加状态指针）。
- 决策登记：`OPEN-DECISIONS.md` 2026-10-20 条目（2026-10-28 采纳、2026-10-29 落地并更正码值）。
- 实现：`DraftCommitService` 第 6b 步；`ErrorCode.SELECTION_ITEM_UNKNOWN`；`GlobalExceptionHandler.statusFor`（409）。
- 门禁：`DraftCommitServiceTest` 三个用例（缺项 → 40904 + 零写入 / `REJECT` → 正常提交 / 未知原因 → 40001）；`SelectionReasonContractTest`（取值域封闭与无裸字面量）。
- 前端：`web/src/utils/errorCodes.ts` 40904 映射 + 双语 `errors.selectionItemUnknown`。
