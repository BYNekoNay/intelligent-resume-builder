# ADR-008: 配置绑定一律用 @ConfigurationProperties，禁止 @Value + SpEL map 字面量

## Status: Accepted (2026-09-25)

## Background

`Normalizer` 原先这样注入同义词词典：

```java
public Normalizer(
        @Value("#{${app.scoring.synonym-dictionary:{}}}") Map<String, List<String>> synonymDictionary) {
```

这段写法**看起来**很常用，实际对**嵌套 YAML** 完全无效：

1. YAML 里词典写成
   ```yaml
   app.scoring.synonym-dictionary:
     java: ["java", "jdk", "openjdk"]
     spring: ["spring", "spring boot", "spring cloud"]
   ```
   Spring 的 YAML 加载器会把它**展平成索引键**：`app.scoring.synonym-dictionary.java[0]`、
   `...[1]`、`app.scoring.synonym-dictionary.spring[0]` …… **不存在**名为
   `app.scoring.synonym-dictionary` 的单个键。
2. 于是 `${app.scoring.synonym-dictionary:{}}` 解析不到 → 走默认值 `{}`；
   外层 SpEL `#{...}` 再把 `{}` 当 map 字面量求值 → **得到空 Map**。
3. 该写法只对「properties 格式的单行 map 字面量」（`k={a:['x'],b:['y']}`）有效，
   而本项目的配置风格是嵌套 YAML。

**后果**：同义词词典在**整个运行时恒为空**，且**没有任何日志或启动失败** ——
配置看起来完全正常（同一个类里的标量属性 `@Value("${app.scoring.rule-version}")` 是能正常读取的，
只有 Map 这条路径失效）。

具体危害：JD 关键词来自配置词典、可能是含空格的**多词短语**（如 `Spring Boot`）。
词典本应把它们归一为 `spring` 从而命中简历中的 `spring`；
词典为空后，`normalize("Spring Boot")` 保持整串 `"spring boot"`，
而简历侧的 token 是按单词切分的 → **`"spring boot"` 永远不可能命中任何 token**，
**即使两侧逐字相同也判缺失** → `keywordCoverage` 被系统性低估，
并产生「建议补充 Spring Boot」这类**与用户简历实际情况相矛盾**的误导建议。

**为什么长期未被发现**：既有的 `NormalizerTest` / `KeywordRuleTest` 都是
手工 `new Normalizer(Map.of(...))` 注入词典，**完全绕过了配置绑定** ——
测试给了词典，运行时没有，于是缺陷在两侧都"看不见"。

## Decision

1. **凡需要绑定 Map / List / 嵌套结构的配置，一律使用 `@ConfigurationProperties`**，
   与本项目既有的 `JdParserProperties`（`app.job.parser`）保持一致；
   **禁止** `@Value("#{${...}}")` 这类 SpEL + 占位符 map 字面量技巧。
2. 新增 `ScoringProperties`（`@ConfigurationProperties(prefix = "app.scoring")`）承载同义词词典；
   `Normalizer` 保留一个接受 `Map` 的构造器供单元测试显式构造，Spring 侧用标注 `@Autowired`
   的构造器委托给它。
3. **必须有一条测试断言"配置真的被绑定"**：新增 `ScoringDictionaryBindingIT`
   （`@SpringBootTest` + `@ActiveProfiles("test")`），断言词典非空且
   `normalize("Spring Boot") == "spring"`。
4. 在 `application.yml` 的 `synonym-dictionary` 上方写明**不要改回那种写法**的原因。

## Consequences

**正面**
- 词典真正生效：`keywordCoverage` 在实测中由 **20.0 提升到 40.0**（与"20 应为 40"的预期精确吻合）。
- 同类风险被封住：任何后续新增的 Map/嵌套配置若误用 `@Value + SpEL`，`ScoringDictionaryBindingIT`
  这类"绑定非空"断言会直接失败。
- 与既有 `JdParserProperties` 风格统一，减少认知负担。

**负面**
- 每个配置域需要多一个 `*Properties` 类（少量样板代码）。
- `Normalizer` 出现两个构造器，需注意 `@Autowired` 标注位置；已在注释中说明用途分工。
- 该写法对**扁平 properties 单行 map** 其实可用，全面禁用会牺牲一点灵活性 —— 但项目统一 YAML 风格下，
  收益（消除一类静默失败）远大于损失。

## Related ADRs

- ADR-002（模型链调度）：同属"配置存在但未生效"的风险族，其教训是"单点验证不能外推为全局结论"
- `docs/reviews/2026-09-25-cloud-functional-test.md` §6（缺陷发现与复现证据）
- `docs/reviews/2026-09-25-ats-keyword-coverage-fix.md`（修复与验证）
