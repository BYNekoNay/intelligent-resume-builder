# T09 — JD 规则覆盖度

> 对应 `docs/13-MVP实施契约与任务卡.md` §5 T09。
>
> 必读:`docs/agent-tasks/T00-通用执行前置条件.md`、`T01..T08`(全部)。

---

## 1. 任务卡摘要

```text
任务名称:JD 规则覆盖度
目标:计算关键词、技能和经历规则覆盖,并保存规则版本和证据。
当前阶段与优先级:第二阶段 / P0
来源文档:02 §5.13、04 §3.7、05 §10
允许范围:scoring 模块,规则引擎,DTO/服务/控制器/测试,前端规则覆盖度展示页。
禁止:调用 LLM 计分;修改 match_result 表结构;对外宣称 ATS 通过率或录用概率。
完成定义:空 JD、空简历、大小写/同义词、重复关键词、极端关键词堆砌、规则版本和解释稳定全部有测试;用户文案统一为「JD 规则覆盖度」并显示「非企业 ATS 结果、非录用概率」。
```

---

## 2. 本卡依赖

### 2.1 前置 Tnn

- [ ] T01 完成(`match_result` 表已建)
- [ ] T02 完成
- [ ] T03 完成(简历版本可用)
- [ ] T05 完成(JD 解析可用,`parsed_keywords_json` 含 `version` 与 `data`)

### 2.2 外部依赖

- [ ] MySQL 已启动

---

## 3. 目标文件清单

```text
server/src/main/java/com/intelligentresume/scoring/domain/MatchResult.java                  新增
server/src/main/java/com/intelligentresume/scoring/repository/MatchResultRepository.java   新增
server/src/main/java/com/intelligentresume/scoring/rule/RuleVersion.java                   新增(常量 v1.0.0)
server/src/main/java/com/intelligentresume/scoring/rule/KeywordRule.java                   新增
server/src/main/java/com/intelligentresume/scoring/rule/SkillRule.java                     新增
server/src/main/java/com/intelligentresume/scoring/rule/ExperienceRule.java                新增
server/src/main/java/com/intelligentresume/scoring/rule/RuleRegistry.java                  新增
server/src/main/java/com/intelligentresume/scoring/rule/Normalizer.java                    新增(归一化 + 同义词)
server/src/main/java/com/intelligentresume/scoring/service/ScoringService.java             新增
server/src/main/java/com/intelligentresume/scoring/service/ResumeKeywordExtractor.java     新增
server/src/main/java/com/intelligentresume/scoring/dto/MatchRequest.java                   新增
server/src/main/java/com/intelligentresume/scoring/dto/MatchResponse.java                  新增
server/src/main/java/com/intelligentresume/scoring/dto/Explanation.java                    新增
server/src/main/java/com/intelligentresume/scoring/controller/ScoringController.java        新增
server/src/test/java/com/intelligentresume/scoring/service/ScoringServiceTest.java         新增
server/src/test/java/com/intelligentresume/scoring/rule/KeywordRuleTest.java                新增
server/src/test/java/com/intelligentresume/scoring/rule/NormalizerTest.java                 新增
server/src/test/java/com/intelligentresume/scoring/controller/ScoringControllerIT.java     新增

web/src/api/scoring.ts                                          新增
web/src/views/MatchResultView.vue                               新增
web/src/stores/matchResult.ts                                   新增
web/src/router/index.ts                                         修改
```

> 禁止触碰:`ai/**`、`careermaterial/**`、`resume/**`、`jobdescription/**`、`export/**`、`system/**`、`auth/**`、`common/**` 中除本卡新增之外的代码;`pdf-service/**`;`docs/01..13`;`docs/agent-tasks/**`;`db/migration/**`;`BaseEntity.java`;`application.yml`(除新增 `app.scoring.*` 外)。
>
> **本卡严禁**:
>
> 1. 调用 LLM 计算分数
> 2. 把分数描述为 ATS 通过率、面试概率、录用概率
> 3. 修改 `match_result` 表结构(已建好,字段必须保留)

---

## 4. 包结构与命名

```text
server/src/main/java/com/intelligentresume/scoring/
├── controller/ScoringController.java
├── service/
│   ├── ScoringService.java
│   └── ResumeKeywordExtractor.java
├── rule/
│   ├── RuleVersion.java
│   ├── RuleRegistry.java
│   ├── KeywordRule.java
│   ├── SkillRule.java
│   ├── ExperienceRule.java
│   └── Normalizer.java
├── domain/MatchResult.java
├── repository/MatchResultRepository.java
└── dto/
    ├── MatchRequest.java
    ├── MatchResponse.java
    └── Explanation.java
```

---

## 5. 配置项

### 5.1 `server/src/main/resources/application.yml`(修改)

```yaml
app:
  scoring:
    rule-version: "v1.0.0"
    weights:
      keyword: 0.4
      skill: 0.4
      experience: 0.2
    synonym-dictionary:
      java: ["java", "jdk", "openjdk"]
      spring: ["spring", "spring boot", "spring cloud"]
      mysql: ["mysql", "mariadb"]
      postgres: ["postgres", "postgresql"]
      redis: ["redis"]
      k8s: ["k8s", "kubernetes"]
      microservice: ["microservice", "微服务"]
      # ...
    user-disclaimer: "本结果为 JD 规则覆盖度,非企业 ATS 结果、非录用概率"
```

---

## 6. 数据库变更

无。

---

## 7. 关键代码骨架

### 7.1 实体

```java
@Entity
@Table(name = "match_result")
public class MatchResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "resume_version_id", nullable = false)
    private Long resumeVersionId;

    @Column(name = "job_description_id", nullable = false)
    private Long jobDescriptionId;

    @Column(name = "total_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal totalScore;

    @Column(name = "keyword_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal keywordScore;

    @Column(name = "skill_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal skillScore;

    @Column(name = "experience_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal experienceScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "explanation_json", nullable = false, columnDefinition = "json")
    private Map<String, Object> explanationJson;

    @Column(name = "rule_version", nullable = false, length = 32)
    private String ruleVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // getters / setters
}
```

### 7.2 Repository

```java
public interface MatchResultRepository extends JpaRepository<MatchResult, Long> {
    Optional<MatchResult> findByIdAndUserId(Long id, Long userId);
    List<MatchResult> findByResumeVersionIdOrderByCreatedAtDesc(Long resumeVersionId);
}
```

> 注意:`match_result` 没有 `user_id` 字段(由 V1 DDL 决定)。跨用户校验通过 `resume_version.resume.userId` 间接完成。Repository 提供 `findByIdAndUserId` 需用 `@Query` 显式 join。

### 7.3 DTO

```java
public record MatchRequest(
    @NotNull Long resumeVersionId,
    @NotNull Long jobDescriptionId
) {}

public record Explanation(
    List<String> matched,         // 完全匹配(归一化后)
    List<String> partialMatched,  // 同义词命中
    List<String> missing,         // JD 中存在但简历中无
    List<String> suggestions,     // 改进建议
    String disclaimer             // 必须等于 app.scoring.user-disclaimer
) {}

public record MatchResponse(
    Long matchResultId,
    BigDecimal totalScore,
    BigDecimal keywordScore,
    BigDecimal skillScore,
    BigDecimal experienceScore,
    Explanation explanation,
    String ruleVersion
) {}
```

### 7.4 Normalizer

```java
@Component
public class Normalizer {
    public String normalize(String token);
    // 规则:
    //   1. 转小写
    //   2. 去标点(保留字母数字中文)
    //   3. 多空格折叠
    //   4. 同义词归一:用 synonym-dictionary 第一个 entry 作为 canonical

    public List<String> tokenize(String text);
    // 简单:中文按字符,英文按 \W+ 分词
}
```

### 7.5 ResumeKeywordExtractor

```java
@Service
public class ResumeKeywordExtractor {

    public Set<String> extract(ResumeVersion version);
    // 抽取来源:
    //   1. basics.label, basics.summary
    //   2. work[*].position, highlights[*]
    //   3. projects[*].name, description, highlights[*]
    //   4. skills[*].name, keywords[*]
    //   5. certificates[*].name
    // 不递归太深,只取一级数组的可见字段
    // 输出:normalized token 集合
}
```

### 7.6 KeywordRule / SkillRule / ExperienceRule

每个 rule 实现:

```java
public interface ScoringRule {
    String name();
    BigDecimal score(Set<String> jdTokens, Set<String> resumeTokens, NormalizedJd jdMeta);
    List<String> matched(Set<String> jdTokens, Set<String> resumeTokens);
    List<String> partialMatched(Set<String> jdTokens, Set<String> resumeTokens);
    List<String> missing(Set<String> jdTokens, Set<String> resumeTokens);
}
```

- **KeywordRule**:对 JD parsed.keywords 计算 matched / missing;部分命中通过 synonym dictionary 计入 partialMatched。
- **SkillRule**:只针对 `materialType=SKILL` 的关键词;命中规则更严格(完全匹配)。
- **ExperienceRule**:对 JD parsed.requirements 中包含 "X 年以上经验" 的项,检查简历 work 总年限是否达到;若 JD 没有要求,experience_score 默认 100。

分数计算:

```text
keyword_score    = matched / (matched + missing) * 100     (若无 missing,满分 100)
skill_score      = 上述逻辑对 skills 子集
experience_score = 见上
total_score      = 0.4 * keyword + 0.4 * skill + 0.2 * experience
```

### 7.7 ScoringService

```java
@Service
public class ScoringService {

    public MatchResponse score(MatchRequest req, Long userId);
    public MatchResult getLatest(Long matchResultId, Long userId);

    // score 实现:
    // 1. 校验 resumeVersion 归属(跨用户 NOT_FOUND)
    // 2. 校验 jobDescription 归属
    // 3. 读 jd.parsed_keywords_json;若不存在或 version 不匹配,先调 JdKeywordParser 重新解析
    // 4. ResumeKeywordExtractor 抽取 resume 关键词
    // 5. 计算各项分数 + 解释
    // 6. 写 match_result, rule_version 来自 RuleVersion.CURRENT(校验与 app.scoring.rule-version 一致)
    // 7. 返回 MatchResponse,explanation.disclaimer = app.scoring.user-disclaimer
}
```

### 7.8 Controller 路由

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/scoring/match` | 计算并保存 |
| GET | `/api/scoring/results/{id}` | 查询 |

### 7.9 关键不变量

- **不调用 LLM**:全部规则计算,无外部模型调用
- **disclaimer 一致**:`Explanation.disclaimer` 必须等于 `app.scoring.user-disclaimer`(测试断言)
- **rule_version 一致**:每次 `score` 写入的 `match_result.rule_version` 等于 `app.scoring.rule-version`;GET 时也带上,前端必须显示
- **稳定**:相同输入(同一 resume_version + JD + 同一规则版本)必须返回相同分数与解释
- **空输入**:空 JD → keyword/skill/experience 全 100,missing=[],suggestions=["JD 未解析出关键词"]
- **空简历**:JD 完整 → keyword/skill/experience 都低分,missing 全量
- **跨用户**:任何路径走 userId 校验,返回 `NOT_FOUND`
- **不宣称**:`Explanation` 字段中禁止出现「ATS 通过」「面试概率」「录用概率」等字样

---

## 8. 前端变更

```text
web/src/api/scoring.ts                              新增
web/src/views/MatchResultView.vue                   新增
web/src/stores/matchResult.ts                       新增
web/src/router/index.ts                             修改
```

**UI 最小要求**:

- 顶部展示「**JD 规则覆盖度**」标题 + disclaimer 文案
- 显示总分(0–100)与三个分项
- 「完全匹配」/「同义词命中」/「缺失」三栏分别展示
- 「改进建议」列表
- 显示规则版本号(`rule_version`),例如「规则版本:v1.0.0」

---

## 9. 测试清单

```text
server/src/test/java/com/intelligentresume/scoring/rule/NormalizerTest.java
    @DisplayName("正常路径: 'Spring Boot' 归一化为 'spring'")
    void normalize_springBoot_canonical()

    @DisplayName("正常路径: 中文标点去除")
    void normalize_chinesePunctuation_removed()

    @DisplayName("正常路径: 多空格折叠")
    void normalize_multipleSpaces_folded()

server/src/test/java/com/intelligentresume/scoring/rule/KeywordRuleTest.java
    @DisplayName("正常路径: 完全命中计 matched")
    void matched_counted()

    @DisplayName("正常路径: 同义词命中计 partialMatched")
    void synonym_partialMatched()

    @DisplayName("正常路径: JD 中独有但简历无计 missing")
    void missing_counted()

    @DisplayName("正常路径: 大小写差异不命中 missing")
    void caseInsensitive_noMissing()

    @DisplayName("边界路径: 关键词堆砌(简历出现关键词 50 次)不影响分数")
    void keywordStuffing_ignored()

    @DisplayName("边界路径: 关键词为空时 keyword_score=100,missing=[]")
    void emptyJdKeyword_score100()

server/src/test/java/com/intelligentresume/scoring/service/ScoringServiceTest.java
    @DisplayName("正常路径: 同输入产生稳定分数与解释")
    void score_stableForSameInput()

    @DisplayName("正常路径: 写入 match_result 含 rule_version")
    void score_persistsRuleVersion()

    @DisplayName("正常路径: explanation.disclaimer 与配置一致")
    void score_disclaimerMatchesConfig()

    @DisplayName("边界路径: JD 无解析结果时自动解析并继续")
    void score_jdNotParsed_autoParse()

    @DisplayName("边界路径: 空 JD + 完整简历,total≈100")
    void score_emptyJd_fullResume()

    @DisplayName("边界路径: 空简历 + 完整 JD,total=低分且 missing 包含全部关键词")
    void score_emptyResume_fullJd()

    @DisplayName("失败路径: 跨用户评分返回 NOT_FOUND")
    void score_crossUser_notFound()

    @DisplayName("失败路径: 简历版本或 JD 不存在返回 NOT_FOUND")
    void score_invalidInputs_notFound()

server/src/test/java/com/intelligentresume/scoring/controller/ScoringControllerIT.java
    @DisplayName("POST /api/scoring/match 200 + 返回 matchResultId")
    void postMatch_200()

    @DisplayName("GET /api/scoring/results/{id} 200")
    void getResult_200()

    @DisplayName("GET 跨用户返回 40401")
    void getResult_crossUser_40401()

    @DisplayName("未登录访问 POST 返回 40101")
    void postMatch_unauthenticated_40101()
```

---

## 10. 验证命令

```bash
cd server
mvn -q -DskipTests compile
mvn test
mvn spring-boot:run
```

另开终端:

```bash
TOKEN=$(...)

curl -X POST http://localhost:8080/api/scoring/match \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"resumeVersionId":1,"jobDescriptionId":1}'

# 期望: code=0, data.totalScore 数字, explanation.disclaimer 等于配置的文案
# 注意:数据中绝不应出现 ATS 通过率 / 面试概率等字样
```

---

## 11. 停止条件

补充 `T00 §6`:

- [ ] 调用 LLM 计分
- [ ] 修改 `match_result` 字段
- [ ] 输出包含「ATS 通过率」「面试概率」「录用概率」字样
- [ ] 跨用户返回 40301
- [ ] 同输入产生不同分数(rule 不稳定)

---

## 12. 完成报告

```text
任务 ID:T09-JD 规则覆盖度
状态:DONE / BLOCKED
修改文件: <按 §3 实际改动列出>

未修改的禁止范围:
  - LLM 计分:未引入
  - match_result 表结构:未改
  - 跨用户 40301:未发生
  - 风险话术:未出现

契约来源:
  - 02 §5.13(简历匹配评分规则)
  - 04 §3.7(match_result 表)
  - 05 §10(评分接口)
  - 07 §5.8(评分测试)
  - 12 §3 + 13 §3(不可违反规则 + 全局不变量)

先失败的测试或现状基线:
  - 基线:ScoringService 不存在

验证命令与结果:
  - mvn test -> Tests run: N, Failures: 0
  - 集成测试 POST /scoring/match -> 200 + 包含 disclaimer

权限/归属/幂等/失败场景证据:
  - 必测 1(空 JD):通过
  - 必测 2(空简历):通过
  - 必测 3(大小写/同义词):通过
  - 必测 4(重复关键词堆砌):通过
  - 必测 5(规则版本稳定):通过
  - 必测 6(解释稳定):通过

文档是否需要同步: 否

剩余风险:
  - 同义词词典覆盖面有限;真实数据需要持续维护
  - ExperienceRule 简化处理(只检查 work 总年限);M3 后可补项目年限
```