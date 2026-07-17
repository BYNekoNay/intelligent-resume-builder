# T05 — JD 管理

> 对应 `docs/13-MVP实施契约与任务卡.md` §5 T05。
>
> 必读:`docs/agent-tasks/T00-通用执行前置条件.md`、`T01`、`T02`。

---

## 1. 任务卡摘要

```text
任务名称:JD 管理
目标:完成 JD CRUD 和确定性规则解析。
当前阶段与优先级:第二阶段 / P0
来源文档:02 §5.4、04 §3.5、05 §6
允许范围:job 模块、JD DTO/实体/仓库/测试、确定性规则解析器、前端 JD 列表/输入页。
禁止:在本任务调用大模型;评分;PDF;AI。
完成定义:原文保留、重复解析覆盖解析结果但不覆盖原文、空文本、超长文本、跨用户和软删全部有测试。
```

---

## 2. 本卡依赖

### 2.1 前置 Tnn

- [ ] T01 完成(`job_description` 表已建)
- [ ] T02 完成(鉴权可用)

### 2.2 外部依赖

- [ ] MySQL 已启动

---

## 3. 目标文件清单

```text
server/src/main/java/com/intelligentresume/jobdescription/domain/JobDescription.java        新增
server/src/main/java/com/intelligentresume/jobdescription/repository/JobDescriptionRepository.java 新增
server/src/main/java/com/intelligentresume/jobdescription/dto/CreateJobDescriptionRequest.java 新增
server/src/main/java/com/intelligentresume/jobdescription/dto/UpdateJobDescriptionRequest.java 新增
server/src/main/java/com/intelligentresume/jobdescription/dto/JobDescriptionSummary.java     新增
server/src/main/java/com/intelligentresume/jobdescription/dto/JobDescriptionDetail.java      新增
server/src/main/java/com/intelligentresume/jobdescription/dto/ParsedKeywordsResponse.java    新增
server/src/main/java/com/intelligentresume/jobdescription/service/JdKeywordParser.java       新增(确定性规则)
server/src/main/java/com/intelligentresume/jobdescription/service/JdParserRuleVersion.java   新增(规则版本常量)
server/src/main/java/com/intelligentresume/jobdescription/service/JobDescriptionService.java 新增
server/src/main/java/com/intelligentresume/jobdescription/controller/JobDescriptionController.java 新增
server/src/test/java/com/intelligentresume/jobdescription/service/JdKeywordParserTest.java  新增
server/src/test/java/com/intelligentresume/jobdescription/service/JobDescriptionServiceTest.java 新增
server/src/test/java/com/intelligentresume/jobdescription/controller/JobDescriptionControllerIT.java 新增

web/src/api/job.ts                                新增
web/src/stores/job.ts                             新增
web/src/views/JobListView.vue                     新增
web/src/views/JobEditorView.vue                   新增
web/src/router/index.ts                           修改
```

> 禁止触碰:`ai/**`、`careermaterial/**`、`resume/**`、`scoring/**`、`export/**`、`system/**`、`auth/**`、`common/**` 中除本卡新增之外的代码;`pdf-service/**`;`docs/01..13`;`docs/agent-tasks/**`;`db/migration/**`;`BaseEntity.java`;`application.yml`(除新增 `app.job.*` 外)。
>
> **本卡严禁调用 Spring AI / 任何 LLM**。所有解析必须是确定性规则。

---

## 4. 包结构与命名

```text
server/src/main/java/com/intelligentresume/jobdescription/
├── controller/JobDescriptionController.java
├── service/
│   ├── JobDescriptionService.java
│   ├── JdKeywordParser.java
│   └── JdParserRuleVersion.java
├── domain/JobDescription.java
├── repository/JobDescriptionRepository.java
└── dto/
    ├── CreateJobDescriptionRequest.java
    ├── UpdateJobDescriptionRequest.java
    ├── JobDescriptionSummary.java
    ├── JobDescriptionDetail.java
    └── ParsedKeywordsResponse.java
```

---

## 5. 配置项

### 5.1 `server/src/main/resources/application.yml`(修改)

```yaml
app:
  job:
    jd-text:
      max-length: 5000           # 字符数上限
      min-length: 20             # 太短视为空,允许解析但关键词为空
    parser:
      rule-version: "v1.0.0"
      keyword-dictionary:
        - "Java"
        - "Spring Boot"
        - "Spring Cloud"
        - "MySQL"
        - "PostgreSQL"
        - "Redis"
        - "Kafka"
        - "Docker"
        - "Kubernetes"
        - "微服务"
        - "分布式"
        - "高并发"
        - "JVM"
        - "多线程"
        # 不需要列全;后续按需扩展
      education-keywords:
        - "本科"
        - "硕士"
        - "博士"
        - "Bachelor"
        - "Master"
        - "PhD"
      experience-pattern: "(\\d+)\\s*年(以上)?(?:经验|工作)"
```

> 关键词词典可放在配置文件中,后续 Tnn 若新增词条,只改 `application.yml`,不修改代码。

---

## 6. 数据库变更

无。

---

## 7. 关键代码骨架

### 7.1 实体

```java
@Entity
@Table(name = "job_description")
@SQLRestriction("deleted_at IS NULL")
public class JobDescription extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "title", nullable = false, length = 128)
    private String title;

    @Column(name = "company_name", length = 128)
    private String companyName;

    @Column(name = "jd_text", nullable = false, columnDefinition = "TEXT")
    private String jdText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parsed_keywords_json", columnDefinition = "json")
    private Map<String, Object> parsedKeywordsJson;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    // getters / setters
}
```

### 7.2 Repository

```java
public interface JobDescriptionRepository extends JpaRepository<JobDescription, Long> {
    Optional<JobDescription> findByIdAndUserId(Long id, Long userId);
    List<JobDescription> findByUserIdOrderByUpdatedAtDesc(Long userId);
}
```

### 7.3 DTO

```java
public record CreateJobDescriptionRequest(
    @NotBlank @Size(max = 128) String title,
    @Size(max = 128) String companyName,
    @NotBlank String jdText
) {}

public record UpdateJobDescriptionRequest(
    @Size(max = 128) String title,
    @Size(max = 128) String companyName,
    String jdText
) {}

public record JobDescriptionSummary(Long id, String title, String companyName, LocalDateTime updatedAt) {}

public record JobDescriptionDetail(
    Long id, String title, String companyName, String jdText,
    Map<String, Object> parsedKeywordsJson,
    LocalDateTime createdAt, LocalDateTime updatedAt
) {}

public record ParsedKeywordsResponse(
    String role,                    // 第一行非空短行作为 role(可选)
    List<String> keywords,          // 词典命中
    List<String> requirements       // 提取的经验年限、教育等
) {}
```

### 7.4 JdKeywordParser 关键方法

```java
@Component
public class JdKeywordParser {

    public ParsedKeywordsResponse parse(String jdText, JdParserRuleVersion version);
    // 实现要点:
    // 1. 关键词:在词典中大小写不敏感匹配(Java/java/JAVA 算一个),保留词典原始大小写
    // 2. 经验:正则匹配 "X 年(以上)?",输出 ["X年以上经验"]
    // 3. 教育:词典命中 ["本科","硕士",...]
    // 4. role:取 jdText 第一行非空内容,长度 > 60 则截断
    // 5. 全部去重,保持原顺序
    // 6. 输入空或只含空白:返回 role=null, keywords=[], requirements=[]
}
```

### 7.5 JdParserRuleVersion

```java
public final class JdParserRuleVersion {
    public static final String CURRENT = "v1.0.0";
    private JdParserRuleVersion() {}
}
```

> 每次解析结果与版本号一起写入 `parsed_keywords_json`:`{"version": "v1.0.0", "data": {...}}`,便于 T09 复用与回溯。

### 7.6 Service 关键方法

```java
@Service
public class JobDescriptionService {
    public JobDescriptionDetail create(CreateJobDescriptionRequest req, Long userId);
    public List<JobDescriptionSummary> list(Long userId);
    public JobDescriptionDetail get(Long id, Long userId);
    public JobDescriptionDetail update(Long id, UpdateJobDescriptionRequest req, Long userId);
    public void softDelete(Long id, Long userId);

    public ParsedKeywordsResponse parse(Long id, Long userId);
    // parse 实现:
    // 1. 查询 JD(校验归属)
    // 2. 调 JdKeywordParser
    // 3. 写回 parsed_keywords_json(覆盖),jd_text 不动
    // 4. 返回 ParsedKeywordsResponse(包含 version 与 data)
}
```

### 7.7 Controller 路由

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/jobs` | 创建 |
| GET | `/api/jobs` | 列表 |
| GET | `/api/jobs/{id}` | 详情 |
| PUT | `/api/jobs/{id}` | 更新 |
| DELETE | `/api/jobs/{id}` | 软删 |
| POST | `/api/jobs/{id}/parse` | 解析关键词(返回并写回) |

### 7.8 关键不变量

- **`jd_text` 原文永不覆盖**:即使重复调用 `/parse`,原文不变
- **解析可重复**:`parsed_keywords_json` 每次重新生成(覆盖)
- **解析可空**:空文本返回空 keywords,但不报错
- **超长文本**:`jdText.length() > app.job.jd-text.max-length` 返回 `VALIDATION`
- **跨用户**:所有方法按 userId 过滤;跨用户返回 `NOT_FOUND`
- **软删**:设置 `deleted_at`;被 `match_result`/`resume_version.generation_context_json` 引用后,JD 软删,但历史快照保留 `jd_text` 原文

---

## 8. 前端变更

```text
web/src/api/job.ts                  新增
web/src/stores/job.ts               新增
web/src/views/JobListView.vue       新增
web/src/views/JobEditorView.vue     新增
web/src/router/index.ts             修改
```

**UI 最小要求**(`13 §6`):

- 列表页:展示 title + companyName;空态引导新建
- 编辑页:`jdText` 多行 textarea(显示字符计数,>5000 阻止保存);保存后可点「解析」按钮
- 「解析」返回结果在编辑器右侧或下方面板展示关键词与要求

---

## 9. 测试清单

```text
server/src/test/java/com/intelligentresume/jobdescription/service/JdKeywordParserTest.java
    @DisplayName("正常路径: 大小写不敏感命中关键词,输出词典原始大小写")
    void caseInsensitiveKeywordHit()

    @DisplayName("正常路径: 经验正则匹配 '3 年以上经验'")
    void experienceRegexHit()

    @DisplayName("正常路径: 教育关键词命中 '本科及以上'")
    void educationKeywordHit()

    @DisplayName("边界路径: 空文本返回 role=null,keywords=[]")
    void emptyText_returnsEmpty()

    @DisplayName("边界路径: 没有命中关键词的文本返回空 keywords")
    void noHit_returnsEmptyKeywords()

    @DisplayName("边界路径: role 取第一行非空,>60 字符截断")
    void role_truncatedOver60()

    @DisplayName("正常路径: 重复关键词去重,保持首次出现顺序")
    void deduplicateKeepOrder()

server/src/test/java/com/intelligentresume/jobdescription/service/JobDescriptionServiceTest.java
    @DisplayName("正常路径: 创建 JD")
    void create_success()

    @DisplayName("正常路径: 解析覆盖 parsed_keywords_json,不改 jd_text")
    void parse_overwritesKeywordsJson_keepsJdText()

    @DisplayName("失败路径: 跨用户 parse 返回 NOT_FOUND")
    void parse_crossUser_notFound()

    @DisplayName("失败路径: 软删后 parse 返回 NOT_FOUND")
    void parse_afterSoftDelete_notFound()

    @DisplayName("失败路径: jdText 超过 max-length 返回 VALIDATION")
    void create_oversizedText_validationFails()

    @DisplayName("失败路径: 软删后 history 快照仍可解析(只读)")
    void historySnapshot_readableAfterSoftDelete()

server/src/test/java/com/intelligentresume/jobdescription/controller/JobDescriptionControllerIT.java
    @DisplayName("POST /api/jobs 201")
    void postCreate_201()

    @DisplayName("GET /api/jobs 返回本人列表")
    void getList_returnsOwn()

    @DisplayName("POST /api/jobs/{id}/parse 200 + 返回 ParsedKeywordsResponse")
    void postParse_200()

    @DisplayName("未登录访问 POST 返回 40101")
    void postWithoutAuth_40101()
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
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
    -H "Content-Type: application/json" \
    -d '{"username":"alice","password":"correcthorse"}' | jq -r '.data.accessToken')

# 创建 JD
curl -X POST http://localhost:8080/api/jobs \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{
      "title":"Java后端工程师",
      "companyName":"某科技公司",
      "jdText":"负责 Spring Boot 微服务开发,熟悉 MySQL/Redis,3 年以上经验,本科及以上学历。"
    }'

# 解析
curl -X POST http://localhost:8080/api/jobs/1/parse \
    -H "Authorization: Bearer $TOKEN"
# 期望: code=0, data.keywords 包含 Spring Boot/MySQL/Redis/微服务, requirements 包含 3年以上经验 与 本科及以上
```

---

## 11. 停止条件

补充 `T00 §6`:

- [ ] 需要调用任何 LLM(本卡禁止)
- [ ] 需要修改 `parsed_keywords_json` 后丢失原文
- [ ] 需要新增字段超出 `04 §3.5`
- [ ] 解析器需要递归或上下文语义(超出确定性规则)

---

## 12. 完成报告

```text
任务 ID:T05-JD 管理
状态:DONE / BLOCKED
修改文件: <按 §3 实际改动列出>

未修改的禁止范围:
  - 大模型调用:未引入
  - 评分/PDF/AI 模块:未触碰
  - jd_text 覆盖:未发生

契约来源:
  - 02 §5.4(JD 管理)/§7.3(AI 数据处理要求)
  - 04 §3.5(job_description 表)
  - 05 §6(JD 接口)
  - 07 §5.5(JD 测试)
  - 13 §3 全局不变量

先失败的测试或现状基线:
  - 基线:JdKeywordParserTest 编译失败,因类不存在

验证命令与结果:
  - mvn test -> Tests run: N, Failures: 0
  - 集成测试 CRUD + parse -> 全部符合预期

权限/归属/幂等/失败场景证据:
  - 必测 1(原文保留):1 个测试通过(覆盖式 parse 不改 jd_text)
  - 必测 2(重复解析覆盖):1 个测试通过
  - 必测 3(空文本):1 个测试通过
  - 必测 4(超长文本):1 个测试通过
  - 必测 5(跨用户):1 个测试通过
  - 必测 6(软删):1 个测试通过

文档是否需要同步: 否

剩余风险:
  - 关键词词典覆盖面有限,真实 JD 可能漏词;后续按真实数据扩展词典
  - 历史快照追溯测试本卡用只读断言;T09 后跑回归验证
```