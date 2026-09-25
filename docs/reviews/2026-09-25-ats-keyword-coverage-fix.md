# ATS 关键词覆盖度修复报告（2026-09-25）

> 触发：`docs/reviews/2026-09-25-cloud-functional-test.md` §6 确认的 P1 缺陷
> （`docs/decisions/OPEN-DECISIONS.md` 登记项 E3-F1，原阻塞条件"冻结基线"已随 ATS 实验收口消失）
> 原则：先定位根因、再动手；每处修复都配"防过度修复"的反向断言；改完在真实环境复测同一份数据

---

## 0. 一页结论

| 项 | 结果 |
| --- | --- |
| 修复的缺陷数 | **3 个**（同一根因族：**配置/字段的消费方不完整**） |
| 真实环境效果 | `keywordCoverage` **20.0 → 40.0**（与"20 应为 40"的预期精确吻合） |
| | `skillCoverage` **20.0 → 40.0** |
| 误导性建议 | 「建议补充 Spring Boot」**已消失**（用户简历里本来就有） |
| 防过度修复 | 真正缺失的 `MySQL` / `Redis` / `高并发` **仍正确判缺失** |
| 后端测试 | **652 个，0 失败 0 错误**（修复前 640，新增 12） |
| PDF 服务测试 | **24 个全绿**（修复前 13+，新增 6 条 skills 用例） |
| 导出回归 | 7/7 模板真实导出正常，非 AI 链路 27 项全量回归通过 |

---

## 1. 缺陷清单（同一根因族）

三个缺陷的表面现象不同，根因同类：**某个字段/配置在 schema 里是规范的，但消费方只实现了一部分**。

| # | 缺陷 | 用户可见影响 | 根因 |
| --- | --- | --- | --- |
| **A** | 同义词词典运行时**恒为空** | 多词关键词（`Spring Boot`）**逐字相同也判缺失** → 规则分被系统性低估 | `@Value("#{${app.scoring.synonym-dictionary:{}}}")` 对**嵌套 YAML** 无效（详见 ADR-008） |
| **B** | 技能区字段读错 | `skillCoverage` 恒低，且给出"建议在技能部分补充 Spring Boot"这种**与简历矛盾**的建议 | `ResumeKeywordExtractor` 只读 `skills[*].keywords`，而**规范字段是 `items`**（见 `JobGenerationPromptBuilder` 的示例结构） |
| **C** | 导出 PDF 丢弃技能项 | 技能区只输出分组名，**具体技能整批丢失** | PDF 模板只渲染 `item.name`，从未引用 `items` / `keywords` |

### 1.1 缺陷 C 的实测证据（渲染 HTML 直取，不靠推断）

在部署的 pdf-service 上渲染同一份技能数据：

| 简历 skills 结构 | 修复前渲染结果 | 修复后 |
| --- | --- | --- |
| **规范结构** `{name:"Java", category:"后端", items:["Java","Spring Boot"], level:"熟练"}` | `<span>Java</span>` —— **丢失 Spring Boot** | `<span>Java</span><span>Spring Boot</span>` ✅ |
| 别名结构 `{name:"Java", keywords:["Java","Spring Boot"]}` | 同样丢失 | 已渲染 ✅ |
| 纯字符串 `["Java","Spring Boot"]` | 两条都在（唯一正常路径） | 不变 ✅ |

> 规范字段的依据：`JobGenerationPromptBuilder` 的示例结构为
> `"skills": [{"name": "...", "category": "...", "items": [...], "level": "..."}]`；
> 前端的草稿字段门禁 `SCHEMA_FIELDS` 的 skills 段也是 `category / items / level / proficiency / keywords`。
> 即 **`items` 是规范字段，`keywords` 只是别名**。

### 1.2 为什么这些缺陷长期没被发现（测试盲区）

| 盲区 | 说明 |
| --- | --- |
| **单测绕过了配置绑定** | `NormalizerTest` / `KeywordRuleTest` 都手工 `new Normalizer(Map.of(...))` 注入词典 —— **测试给了词典，运行时没有**，两边都"看不见"问题 |
| **单测手工构造 token 集合** | `KeywordRuleTest.synonym_partialMatched` 直接传 `resumeTokens = Set.of("spring")`，从未让 `ResumeKeywordExtractor` 从真实简历 JSON 走一遍 |
| **PDF 测试没有 skills 断言** | 原 `templates.test.js` 用 `SKILL_MARKER` 只验"技能区存在"，不验技能项是否渲染 |

---

## 2. 修复内容

### 2.1 缺陷 A：配置绑定（新增 `ScoringProperties`）

```java
@Component
@ConfigurationProperties(prefix = "app.scoring")
public class ScoringProperties {
    private Map<String, List<String>> synonymDictionary = new LinkedHashMap<>();
    // getter / setter
}
```

`Normalizer` 改为经 Spring 注入 `ScoringProperties`（保留 `Map` 构造器供单测）。
同时在 `application.yml` / `application-test.yml` 的 `synonym-dictionary` 上方加注释说明
**不要改回 `@Value` + SpEL 的写法**及其原因。→ 立为 **ADR-008**。

### 2.2 缺陷 B：技能字段抽取（`ResumeKeywordExtractor`）

- 新增读取 `skills[*].items`（规范字段）与 `skills[*].keywords`（别名），两者都读；
- `addText` 扩展为**同时接受数组**，使 `items: [...]` / `keywords: [...]` 走同一套清洗规则；
- 保留对 `skills: ["Java", ...]` 纯字符串项的兼容。

### 2.3 缺陷 C：PDF 技能渲染（`pdf-service/src/templates/classic.js`）

新增 `skillLabels(item)`：按 `name → keyword → items → keywords` 收集标签、
**去重并保持首次出现顺序**（AI 常把同一技能同时写进 `name` 与 `items`，不去重会叠字），
再逐个渲染为 `<span>`（沿用 `text()` 转义，防注入）。

### 2.4 缺陷 A/B 的关联修复：多词关键词匹配（`Normalizer`）

即使词典生效，仍有一类关键词（未进词典的多词短语，如 `Spring Security`）无路可走。
故在 `Normalizer` 增加：

- `tokenizeOrdered`：一趟扫描的**有序**分词（原 `tokenize` 分两趟扫描会丢失顺序，词组匹配需要顺序）；
- `tokenizeWithPhrases`：在**同一段文本内连续**的拉丁/数字词之间生成词组（最多 5 词），
  **中文序列不参与组词**（中文无空格分隔，且本身已是完整 token），
  因此 `精通 Spring Boot 与 Redis` 产出 `spring boot`，但不会产出 `boot redis` 这类跨中文伪词组；
- `ResumeKeywordExtractor.addText` 改用 `tokenizeWithPhrases`。

---

## 3. 回归测试（每处修复都配反向断言）

| 测试 | 断言 | 拦截什么 |
| --- | --- | --- |
| `ScoringDictionaryBindingIT`（**新增**，`@SpringBootTest`） | 词典**非空**；`normalize("Spring Boot") == "spring"` | 配置注入方式退化（原缺陷 A 的专属回归） |
| `KeywordCoveragePhraseTest`（**新增**，走真实链路） | 简历含 `Spring Boot` → 关键词命中且总分 66.67；**未配置同义词也能命中** | 多词关键词缺失 |
| 同上 | **不相邻的词不得命中**（`Spring` 与 `Security` 分处两个字段） | **修过头变成假命中** |
| 同上 | 真正缺失的 `Kubernetes` / `微服务` 仍判缺失 | 同上 |
| 同上 | `skills[*].items` 被抽取且技能覆盖度满分；`keywords`/纯字符串仍兼容 | 缺陷 B |
| `NormalizerTest`（扩展 4 条） | 有序分词保序；词组只跨连续拉丁词；`maxWords` 生效；中文不组词 | 分词实现回归 |
| `templates.test.js`（**新增 6 条**） | **7 个模板全部**渲染 `items`、别名 `keywords`/`keyword`、纯字符串；`name` 与 `items` 重复时**只渲染一次**；HTML 转义 | 缺陷 C + 叠字/注入回归 |

**测试结果**：后端 **652 tests / 0 failures / 0 errors / 5 skipped**；
PDF 服务 **24 tests / 24 pass**。

---

## 4. 真实环境验证（部署后，同一份简历与 JD）

> ⚠ 复测必须使用**新的 `Idempotency-Key`**：ATS 检查按幂等键缓存，
> 复用旧键会直接返回修复前的历史结果，从而把"未修复"误判为"已修复"。

| 指标 | 修复前 | 修复后 | 判定 |
| --- | --- | --- | --- |
| `keywordCoverage` | 20.0 | **40.0** | ✅ 与预期精确吻合 |
| `skillCoverage` | 20.0 | **40.0** | ✅ |
| `risks` | `缺少 JD 关键词: Spring Boot, MySQL, Redis, 高并发` | `缺少 JD 关键词: MySQL, Redis, 高并发` | ✅ 不再误报 |
| `priorities` | 含"补充 Spring Boot" | `建议补充以下关键词: MySQL, Redis, 高并发` | ✅ 误导性建议消失 |
| 真正缺失项 | MySQL/Redis/高并发 | **仍判缺失** | ✅ 未修过头 |

**渲染侧**：部署后的模板对三种 skills 结构均渲染出 `Spring Boot`（修复前仅纯字符串路径正常）。

**回归**：非 AI 链路 27 项全量复跑通过（含 7/7 模板真实 PDF 导出、归属隔离、边界码）；
后端 652 项测试全绿。

---

## 5. 关于本机 2 个 PDF 测试失败：判定为环境因素，非代码缺陷

本机跑 `npm test` 有 2 个失败（均在 `production-config.test.js`），**经三条证据判定为环境因素**：

| 证据 | 内容 |
| --- | --- |
| ① 最小探针 | `spawnSync(process.execPath, ['-e','process.exit(7)'])` → `status: null`、`error: ... EBUSY` —— **不含任何项目代码**，纯粹是沙箱限制"再派生 node 子进程" |
| ② 换 Node 版本 | 改用系统版 Node（v24.11.1）同样 `EBUSY` —— 与具体二进制无关 |
| ③ 手工复现产品行为 | 用 shell 启动同一场景（`NODE_ENV=production` + 默认口令 `src/server.js`）→ **退出码 1、错误文案正确** —— 正是该测试断言的行为 |
| ④ 服务器侧全绿 | 在 Linux 上 `npm test` → **24/24 通过**（含这 2 条） |

**结论：产品行为正确，失败源于本机沙箱无法 `spawnSync`。** 已在报告中标注，避免后人误当成回归。

> 附带发现（**部署链路的一个小缺口，已于同日修复**）：`templates.test.js` 依赖**仓库根目录**的
> `test-fixtures/resume-all-sections.json`，而部署包原先只上传 `server/`、`web/`、`pdf-service/`，
> 因此该文件在服务器上**从来跑不了**（`ENOENT`；表现为 `templates.test.js` 文件级失败、
> `npm test` 只跑出 8 个用例）。本次首次取得 24/24 基线是手工复制 fixture 后才做到的。
>
> **修复**：`scripts/deploy-direct.sh` 的打包与解压两处加入 `test-fixtures`，
> 落位 `/opt/intelligent-resume/src/test-fixtures/`（**源码树内**，供"在服务器上跑测试"使用；
> `app/` 下按设计不放测试资产）。远程脚本末尾增加用法提示，
> `docs/DEPLOYMENT_DIRECT.md` §5.1 记录完整说明。
>
> **验证**：先手工删除两侧 fixture 复现 `ENOENT`（`# tests 1, pass 0`）→ 重新部署 →
> `/opt/intelligent-resume/src/test-fixtures/` 出现该文件 → 服务器侧
> `cd src/pdf-service && PUPPETEER_SKIP_DOWNLOAD=true npm test` → **24/24 通过**（此前 8 个）。

---

## 6. 未覆盖与残留（如实标注）

| 项 | 说明 |
| --- | --- |
| **历史数据的评分不会自动重算** | 修复只影响**新发起**的 ATS 检查；此前已落库的 `ats_check_result` 仍是旧口径。若需要，可另行提供重算脚本（未做） |
| 词典内容本身未评估 | 本次只修"词典是否生效"，未评估词典覆盖面（如是否该补 `Spring Security`、`Kafka` 等）。多词关键词现已由词组匹配兜底，词典更多是提升**同义词**（partial match）覆盖 |
| `MAX_PHRASE_WORDS = 5` 是经验取值 | 超过 5 词的关键词仍需依赖词典命中；未做数据驱动的取值论证 |
| 未验证真实用户简历 | 仍局限于测试账号与合成数据 |
| PDF 文本层未做逐字提取校验 | PDF 内容流是压缩的，无法直接 grep。本次通过**渲染层 HTML 断言**（PDF 即由该 HTML 生成）+ 导出成功与体积正常来判定，未做 PDF 文本抽取（缺 `pdftotext`） |

---

## 7. 变更记录

| 日期 | 变更 | 原因 |
| --- | --- | --- |
| 2026-09-25 | 初版 | 修复缺陷 A/B/C 并完成真实环境验证 |
