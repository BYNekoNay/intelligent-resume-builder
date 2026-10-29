# 2026-10-23 Web→桌面迁移：真实导出包往返核查与修复

> **性质**：点状审计 + 修复记录（两者在同一份文档内按节区分，见各节标注）。
> **审计时间/基线**：2026-10-23，`master` = `c2fc0d8`（`v0.4.1` 之后 2 个提交），后端 166 个测试文件全绿。
> **单一权威来源**：缺陷结论以本文为准；实现细节以 `ExportPackageAssembler` / `DesktopImportService` /
> `ImportColumnBinder` 与 `DesktopExportImportRoundTripIT` / `ImportCollectionContractTest` 为准。
> **已被本次修复的旧结论**：`docs/RELEASES/v0.4.1.md:86-87` 与 `OPEN-DECISIONS.md:28`
> 把「导入预览不覆盖嵌套集合」列为**唯一**缺口 —— 实测还有三个更严重的缺陷（见 §2），
> 其中两个会让真实导出包**一条都导不进去**。

## 1. 为什么要查（快照）

ADR-012 阶段四（Web→桌面迁移包）在 2026-10-21 标为 ✅，证据是 `DesktopImportServiceIT` 10/10 +
`JsonColumnH2ReadbackIT` 5/5 全绿。但读码时发现一个共同点：**所有用例都是手写导入包**
（JSON 列写成字符串 `"{\"basics\":{...}}"`、`stableId` 逐条手填）。真实链路却是：

1. `AccountExportService.loadExportPayload()` 用 `objectMapper.valueToTree(entity)` 序列化实体；
2. 包写成 `.json` 文件；
3. 桌面端把文件读成 `Map<String,Object>`。

即「真实包」与「测试包」在**参数类型**上是两回事。于是先写一条**穿过真实链路**的往返用例
（`DesktopExportImportRoundTripIT`：造数据 → 真实导出 → 序列化 → 反序列化 → preview → apply → JPA 读回），
再按失败点逐个修。这份文档记录该过程。

## 2. 实测结论：三个缺陷，两个是 P0（快照）

按被发现顺序（也是修复顺序）：

### P0-1 真实导出包**一条稳定 ID 都没有** → 整份导入被拒绝

- **现象**：`preview()` 抛 `IllegalStateException: 包内记录缺少 stableId（集合 jobDescriptions）—— 无法判断是否重复导入，已拒绝整份导入`。
- **根因**：`AccountExportService.injectStableIds()` 用 `element instanceof Map` 判断记录。
  Jackson 2.x 的 `ObjectNode` **只实现 `java.io.Serializable`**（`javap` 实测：
  `public class ObjectNode extends ContainerNode<ObjectNode> implements java.io.Serializable`），
  **不实现** `java.util.Map` → 判断恒为 false → 注入从未发生。
  同一判断在 `buildManifest()` 里还有一份，因此**清单里的 `stableIds` 也是空的**，两处同时失效。
- **另一处叠加**：`careerMaterials` / `jobDescriptions` / `communicationTemplates` / `communicationDrafts` /
  `aiConsents` 五类直接把 **JPA 实体**放进负载（不是 JSON 节点），即便修好上面的判断，
  实体也拿不到 `stableId`。
- **影响**：只要用户有一条岗位记录，Web→桌面迁移 100% 失败。这是**功能级不可用**，不是边角缺口。

### P0-2 JSON 列的 `Map` 被 H2 当成 `JAVA_OBJECT` → 插入直接报错

- **现象**：`DataIntegrityViolationException: Data conversion error converting
  "CAST(X'aced0005737200...' AS JAVA_OBJECT)" (JOB_DESCRIPTION: ""PARSED_KEYWORDS_JSON"" JSON)`。
- **根因**：`ImportColumnBinder.placeholder()` 只对 `String` 返回 `? FORMAT JSON`，`Map` 走普通 `?` →
  `ps.setObject(i, LinkedHashMap)` → H2 按 Java 序列化处理，与 JSON 列类型冲突。
- **为什么此前没被发现**：既有用例的 JSON 列全是手写字符串，正好落在唯一被支持的分支上。

### P1-3 `careerMaterials` 被静默丢弃

- **根因**：同一件事实在 `DesktopImportService` 里维护了三份且互相矛盾 ——
  `SUPPORTED_ORDER`（3 类）、`SUPPORTED_TABLES`（4 类，含 `careerMaterials`）、
  `unsupportedCollections`（拿后者当「已知」）。`careerMaterials` 于是
  **既不预览、也不写入、还不会被判为「不支持」** → 用户的职业资料凭空消失，且没有任何提示。
- **对照事实**：导出侧 `EXPORT_COLLECTIONS` 有 9 类，明确包含 `careerMaterials`；
  `README.md:183` 也宣称「4 类核心集合」。仓库内搜索 `careerMaterial` 在
  `server/src/test/java/com/intelligentresume/desktop/` 命中 **0** —— 零覆盖，所以长期全绿。

## 3. 修复（修复记录）

| # | 改动 | 文件 |
|---|---|---|
| 1 | 稳定 ID 注入与清单装配拆到独立类；判断改为按 **JSON 节点**（不再 `instanceof Map`），并直接改原节点（`valueToTree` 中转会改到副本上） | 新 `auth/service/ExportPackageAssembler.java` |
| 2 | 实体列表统一经 `assembler.nodes()` 转成节点后再进负载 | `auth/service/AccountExportService.java` |
| 3 | JSON 列的值归一化进 `ImportColumnBinder.valueFor()`：非字符串（Map/List）先 `writeValueAsString` 成 JSON 文本，再绑 `? FORMAT JSON`；字符串原样保留 | `desktop/ImportColumnBinder.java` |
| 4 | 集合清单收敛为**唯一注册表**（preview / apply / unsupported 三处共读），并按依赖顺序加入 `careerMaterials` | 新 `desktop/ImportCollectionRegistry.java`、`desktop/DesktopImportService.java` |
| 5 | 新增往返门禁（穿真实导出链路） | 新 `test/.../desktop/DesktopExportImportRoundTripIT.java` |
| 6 | 新增契约门禁：导入集合 == 本版声明的 4 类核心集合 / 名字必须存在于导出集合 / 映射表必须存在于迁移 | 新 `test/.../contract/ImportCollectionContractTest.java` |
| 7 | 行数棘轮：`DesktopImportService` 569→554（下调），`AccountExportService` 334→260 且**移出基线**（已达标 ≤300） | `test/resources/source-size-baseline.txt` |

**`careerMaterials` 选「支持导入」而不是「显式拒绝」的依据**：`career_material` 表唯一外键指向 `user`；
引用它的 `resume_material_reference`、`interview_asset_section` 都不在导出集合内 →
支持导入不会产生悬空外键。代价是「某版本引用过哪些资料」这层关系不随包迁移，需在 UI 提示。

## 4. 验证证据（修复记录，全部来自当场命令输出）

| 验证项 | 结果 |
|---|---|
| 往返 IT（修复前） | **红**：`包内记录缺少 stableId（集合 jobDescriptions）` |
| 往返 IT（修 P0-1 后） | 红：`职业资料必须参与预览……实际：[jobDescriptions, resumes, applications]` |
| 往返 IT（修 P0-2 前） | 红：`Data conversion error converting "CAST(X'aced0005...' AS JAVA_OBJECT)"` |
| 往返 IT（全部修完） | **绿**：`Tests run: 1, Failures: 0, Errors: 0` |
| `DesktopImportServiceIT` | 绿 10/10（无回归） |
| `JsonColumnH2ReadbackIT` | 绿 5/5（无回归） |
| `ImportCollectionContractTest` | 绿 3/3 |
| **门禁承重验证** | 删掉注册表里的 `careerMaterials` 条目 → `ImportCollectionContractTest` **2 条断言变红**（`实际：[applications, jobDescriptions, resumes]`）；还原后 md5 与注入前一致（`dcd6d3d1b02377a5f789aed96db9ed3e`） |
| **棘轮承重**（非刻意注入，是真实被拦） | 改造过程中 `AccountExportService` 涨到 384 行 → `registeredFilesMustNotGrow` 红（`允许 334 行（+50）`）；拆出装配器后降到 260 → `staleBaselineEntriesMustBeRemoved` 红，要求移出基线。两次都按「只许下调」处理，未改大基线 |

## 5. 仍未解决（不要在别处当成已完成）

1. **嵌套集合不参与预览**：`preview()` 只产出顶层记录，而 `insertChildren()` 会写入
   `resumes.versions` / `applications.statusHistory`；并且 `apply()` 只在 ADD 分支调 `insertChildren`，
   **父记录 SKIP/CONFLICT 时子记录根本不写**（比「未预览」更严重）。登记于 `OPEN-DECISIONS.md:28`，本次未改。
   *（2026-10-28 追记，不改上文：**已解决** —— 批次 1.4 让嵌套集合与顶层同规则参与分类，
   且父记录 SKIP/KEEP_LOCAL 时也播种 idMap、子记录照自己的决策写入。守护用例见
   `docs/reviews/2026-10-23-nested-import.md` 与 `docs/05` §18.1。）*
2. **非核心集合显式拒绝**：`interviewSessions` / `interviewAnswerAssets` / `communicationTemplates` /
   `communicationDrafts` / `aiConsents` 有数据时整份导入会被拒绝（这是刻意的「不静默丢」策略，
   但用户侧体验是「有这个包却导不进去」，需产品口径决定后续是否支持）。
3. **未在真实环境手工验过**：本次验证止于 H2 file 测试库。真实链路还需一次
   「测试环境导出账号包 → 桌面模式导入 → 逐项对比」，属发布前必做项。
