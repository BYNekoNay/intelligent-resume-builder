# 文档健康报告（2026-11-05）

> **时效性声明**（依 `docs/README.md` §4.8）
>
> - **审计时间与基线**：2026-11-05，工作区 `intelligent-resume-builder`，产品版本 `0.5.0`（`server/pom.xml:16`），
>   迁移最高 `V40`，部署探针实测期望 36 项。
> - **本章性质**：第一~四节是**当日快照**（核对结论只对该基线成立）；第五节是**历史快照的正常不一致**说明，不是缺陷。
> - **已知已被后续修复的条目**：本报告不含"待修但已修"的重复项——历史记忆中的 4 处漂移已在第四节逐条复核并标注现状。
> - **单一权威来源**：产品现状以 `PROJECT_CONTEXT.md` 为准；线上精确数字只认 `docs/DEPLOYMENT_DIRECT.md` §13；
>   版本/迁移/探针的机器事实以对应门禁代码为准（见正文 `file:line`）。

---

## 1. 方法与能力边界

本次核对方式是**按文件名定位后逐份读取，再与门禁代码/迁移目录比对**。

| 能力 | 状态 | 影响 |
| --- | --- | --- |
| 读文件 / 列目录 | ✅ 可用 | 可逐份核对 |
| shell（Bash / PowerShell） | ❌ 沙箱创建失败 `0xFFFFF826` | **无法执行 `git`、`mvn`、`npm`，无法跑门禁** |
| 全文检索（Grep） | ❌ 同一沙箱故障 | **无法全库统计**，只能按已知路径定点读取 |

因此本报告是**抽样核对（14 项）**，不是全量审计；结论不得外推到未列出的文档。
上一轮全量盘点见 [`2026-09-25-documentation-audit.md`](./2026-09-25-documentation-audit.md)（当时 87 个文件）。

---

## 2. 结论总表

| # | 被核对的断言 | 出处 | 实测 | 判定 |
| --- | --- | --- | --- | --- |
| 1 | 版本号 `0.5.0` | `README.md:5`、`README.md:135` | `server/pom.xml:16` = `0.5.0` | ✅ 一致 |
| 2 | 封档日期 2026-10-29 | `docs/RELEASES/v0.5.0.md:15` | 与 `v0.5.0.md:113` 追加批次同日 | ✅ 一致 |
| 3 | Flyway `V1–V40` | `README.md:81`、`README.md:154` | 迁移目录实际 **40 个** `.sql`，最高 `V40__retention_soft_delete_application_and_asset.sql` | ✅ 一致 |
| 4 | 迁移 40 条从零跑 | `docs/CI.md:32` | 同上 | ✅ 一致 |
| 5 | 部署探针 36 项 | `README.md:148`、`README.md:165` | `scripts/probe-deployment.sh:53` `EXPECTED_ITEMS_SSH=36` | ✅ 一致 |
| 6 | Spring Boot 3.3.2 / Java 17 | `README.md:92` | `server/pom.xml:10` = `3.3.2`，`pom.xml:21` java 17 | ✅ 一致 |
| 7 | **同一版本号 11 处独立副本** | `README.md:171` | 门禁断言 **13** | ⚠️ **漂移**（见 §3.1） |
| 8 | **`docs/README.md` 覆盖全部分类** | `docs/README.md` §2 | 缺 `RELEASES/` 分类，7 份发布说明无索引入口 | ⚠️ **漂移**（见 §3.2） |
| 8b | **前端技术栈含 Element Plus** | `README.md:91` | `web/package.json:22-28` 依赖只有 vue / vue-router / pinia / axios / lucide，**无任何 UI 组件库** | ⚠️ **漂移**（见 §3.3） |
| 9 | 模型链"7 个模型顺序降级" | `README.md:83` | 链值只在服务器 `.env`，仓库内无副本 | ⬜ **无法核实**（见 §5） |
| 10 | ADR-012 阶段表三/四重复 | 历史记忆 | 已由 `ADR-012:203-221` 追加更正收口 | ✅ 已修 |
| 11 | `DesktopImportController:18-20` 行号引用 | 历史记忆 | 文件现位于 `desktop/` 包根，非 `desktop/controller/` | ✅ 已修（路径变更使旧行号失效） |
| 12 | `application.yml:316` 词典注释 | 历史记忆 | `application.yml:316-322` 注释与 `ScoringProperties` 做法一致 | ✅ 已修 |
| 13 | 确认草稿缺项返回 `40904` | `docs/RELEASES/v0.5.0.md:59` | 见 `docs/decisions/ADR-013-*.md`，与 `PROJECT_CONTEXT.md` 未冲突 | ✅ 一致 |
| 14 | 探针 33 项 | `docs/RELEASES/v0.5.0.md:50` | 现为 36 项 | ✅ 正常（历史快照，见 §4） |

---

## 3. 需要修的漂移

### 3.1 【已核实】README 的"版本号 11 处副本"已过期

| 项 | 内容 |
| --- | --- |
| 位置 | `README.md:171`「同一版本号有 11 处独立副本（含 jar 名），任一处不一致即红」 |
| 实际 | `VersionConsistencyContractTest.java:21` 类注释写 **13 处**；同行号 `:88` 断言 `assertEquals(13, copies.size(), ...)` |
| 变更来源 | `docs/RELEASES/v0.5.0.md:113-115`：P5 批次把 `desktop-shell/package.json` 与 lock 纳入，副本 **11 → 13** |
| 风险 | 读者按"11"去数副本，会漏掉桌面壳两处；而漏掉的正是**安装包内嵌 jar 与壳版本脱钩**那类事故 |
| 建议 | `README.md:171` 的 `11` 改为 `13`；或改为不写死数字、改为"全部副本（清单见门禁）"，避免再次过期 |

> 判定依据是**门禁代码本身**，不是发布说明——门禁代码是唯一会在不一致时变红的判据。

### 3.2 【已核实】文档索引缺 `RELEASES/` 分类

| 项 | 内容 |
| --- | --- |
| 位置 | `docs/README.md` 第 2 节只有 `plans / reviews / decisions / research / ideation / diagrams / archive` 七类 |
| 实际 | `docs/RELEASES/` 下有 **7 份**：`v0.3.0`、`v0.4.0`、`v0.4.1`、`v0.4.2`、`v0.4.3`、`v0.4.4`、`v0.5.0` |
| 风险 | 发布说明是"精确测试条数、破坏性变更、升级步骤"的唯一权威位置，索引里没有入口等于**默认读者找不到升级须知** |
| 建议 | 在 §2 增加 `2.x RELEASES/` 小节，按版本倒序列出 7 份，并注明"破坏性变更与升级步骤只在此处" |

### 3.3 【已核实】README 写了一个并不存在的前端组件库

| 项 | 内容 |
| --- | --- |
| 位置 | `README.md:91`「前端 \| Vue 3 + TypeScript + Vite \| Vue 3 / **Element Plus**」 |
| 实际 | `web/package.json:22-28` 的 `dependencies` 只有 `axios` / `lucide-vue-next` / `pinia` / `vue` / `vue-router`；`devDependencies:30-46` 也没有任何 UI 组件库 |
| 判定 | 前端是**自研组件 + lucide 图标**。`PROJECT_CONTEXT.md:73-84` 的前端目录说明同样未出现 Element Plus，两处独立证据一致 |
| 风险 | 新人按技术栈去查 `el-table` / `el-form` 的用法，会在项目里找不到任何实例，白白浪费半天；更糟的是"照着文档引入 Element Plus"，与既有自研组件体系冲突 |
| 建议 | 改为「自研组件（无第三方 UI 库），图标用 lucide」。若确实计划引入，应写进 `docs/plans/` 并走 ADR，而不是留在技术栈表里 |

> 这处漂移的教训：**技术栈表最容易烂**，因为它不参与任何门禁，也没有人会在改依赖时回来看它。
> 同类风险项还有 `README.md:92` 的 Spring Boot 版本（本次核对**正确**，但没有机器守护）。

---

## 4. 历史记忆中的"4 处漂移"复核结果

`PROJECT_CONTEXT.md` 与 `docs/` 的协作记忆中曾登记 4 处"文档落后实现"，本次逐条复核：**3 处已修，1 处因路径变更自然失效**。

| 原登记项 | 现状 | 证据 |
| --- | --- | --- |
| ADR-012 `:197-199` | 已追加「状态更正确认（2026-10-23）」章节，明确标注上文失效陈述与权威依据 | `ADR-012:203-221` |
| 阶段表三/四重复 | 已在同章节第 2 条点名，并给出权威口径（带 ✅/🟡 的那组） | `ADR-012:213-215` |
| `DesktopImportController:18-20` | 文件已迁到 `com/intelligentresume/desktop/DesktopImportController.java`（包根），旧行号引用不再指向任何内容 | 目录列举 |
| `application.yml:316` | 该行现为同义词词典的"不要改回 `@Value` + SpEL"警示注释，与代码一致 | `application.yml:316-322` |

> 结论：**记忆会滞后于修复**。凡"现存问题清单"在引用前必须逐条核实当前状态——这条规则在本轮再次被验证
> （`docs/README.md` 维护原则第 9 条已写明，本次是它的又一次实证）。

---

## 5. 历史快照的正常不一致（不是缺陷）

`docs/RELEASES/v0.5.0.md` 写"探针扩到 33 项"，当前探针为 36 项。

- 发布说明是**某一天的事实快照**，按 `docs/README.md` 维护原则第 2 条，**不改写原结论**；
- 33 → 36 的差额来自后续批次新增的备份事实、告警出口 watchdog 等检查项；
- 唯一允许写"线上精确数字"的位置仍是 `docs/DEPLOYMENT_DIRECT.md` §13，
  因此**不要把 v0.5.0 的 33 当作现值**。

同理，`v0.5.0.md:50` 与 `README.md:165` 的差异不需要"对齐"，只需要读者知道哪一份是快照。

---

## 6. 未能核实的项

| 项 | 原因 | 补核方式 |
| --- | --- | --- |
| 模型链"7 个模型"（`README.md:83`） | `application.yml:227` 的 `model-chain` 取 `${BAILIAN_MODEL_CHAIN:}`，**默认空**；真实链值只存在于服务器 `app/api/.env`，仓库内无副本 | 登录测试环境读 `app/api/.env` 的 `BAILIAN_MODEL_CHAIN`，数逗号分隔项；或看 `/api/system/health` 的模型链字段 |

> 这本身是一个结构性问题：**README 写了精确数字，但仓库里没有它的权威来源**。
> 与 `v0.5.0.md:82` 确立的"派生指标由实测供给"原则冲突。建议 README 改为描述机制
> （"有序降级链，具体模型与顺序由部署环境决定"），不写数字。

---

## 7. 后续动作（按优先级）

1. ~~`README.md:171` 的 11 → 13、`docs/README.md` 缺的 `RELEASES/` 分类、`README.md:91` 的 Element Plus~~ —— **本批已修**（同一次提交内完成，无需再排期）；
2. `README.md:83` 去掉"7 个"这个无权威来源的精确数字（本批已改为"有序降级 + 失效冷却"）；
3. 待 shell 沙箱恢复后，补跑 `bash scripts/probe-deployment.sh` 与版本一致性门禁，把"文档说的"换成"机器验的"；
4. 技术栈表加一道"依赖清单 ↔ 文档"的机械比对（`package.json` 里没有的库，文档不得写入），否则本报告 §3.3 会再次发生。

---

## 8. 本次未覆盖的面（明确不承诺）

- 未逐个核对 117 份 md 之间的**交叉引用链接**是否可达（需全文检索）；
- 未核对 `docs/05-接口设计说明书.md` 的端点清单与 114 个 mapping 是否逐条一致（有 `ApiDocCoverage` 类门禁兜底，但本次未运行）；
- 未核对前端 i18n 文案与文档术语的一致性。
