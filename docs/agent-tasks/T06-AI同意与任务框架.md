# T06 — AI 同意与任务框架

> 对应 `docs/13-MVP实施契约与任务卡.md` §5 T06。
>
> 必读:`docs/agent-tasks/T00-通用执行前置条件.md`、`T01`、`T02`、`T03`、`T04`、`T05`。

---

## 1. 任务卡摘要

```text
任务名称:AI 同意与任务框架
目标:完成 AI 同意、数据库任务、幂等、抢占、租约、重试和状态查询。
当前阶段与优先级:第二阶段 / P0
来源文档:02 §7.3、03 §9.5/9.6、04 §3.1.2/3.8、05 §2.6/§7.5
允许范围:ai 模块的 consent、task、worker、provider 子包;Mock AI Provider;对应 DTO/实体/仓库/测试;前端 ai consent 页面。
禁止:真实外部 AI;岗位定制生成业务逻辑(留给 T07);私有文件;评分。
完成定义:未同意不创建、撤回后不创建、相同幂等键返回原任务、相同键不同请求冲突、工作器重启恢复、租约过期、最大重试、跨用户任务查询全部有测试。
```

---

## 2. 本卡依赖

### 2.1 前置 Tnn

- [ ] T01 完成(`ai_consent`、`ai_task` 表已建)
- [ ] T02 完成(鉴权可用)
- [ ] T03 完成(`@CurrentUserId` 解析可用)
- [ ] T04 完成(`career_material` 可读写)
- [ ] T05 完成(`job_description` 可读写)

### 2.2 外部依赖

- [ ] MySQL 已启动

---

## 3. 目标文件清单

```text
server/src/main/java/com/intelligentresume/ai/consent/domain/AiConsent.java                       新增
server/src/main/java/com/intelligentresume/ai/consent/domain/ConsentStatus.java                  新增(枚举)
server/src/main/java/com/intelligentresume/ai/consent/repository/AiConsentRepository.java        新增
server/src/main/java/com/intelligentresume/ai/consent/dto/GrantConsentRequest.java               新增
server/src/main/java/com/intelligentresume/ai/consent/dto/ConsentResponse.java                   新增
server/src/main/java/com/intelligentresume/ai/consent/service/AiConsentService.java              新增
server/src/main/java/com/intelligentresume/ai/consent/controller/AiConsentController.java        新增

server/src/main/java/com/intelligentresume/ai/task/domain/AiTask.java                            新增
server/src/main/java/com/intelligentresume/ai/task/domain/AiTaskStatus.java                      新增(枚举)
server/src/main/java/com/intelligentresume/ai/task/domain/AiTaskType.java                        新增(枚举)
server/src/main/java/com/intelligentresume/ai/task/domain/ConfirmationStatus.java                新增(枚举)
server/src/main/java/com/intelligentresume/ai/task/repository/AiTaskRepository.java              新增
server/src/main/java/com/intelligentresume/ai/task/dto/CreateAiTaskRequest.java                  新增
server/src/main/java/com/intelligentresume/ai/task/dto/AiTaskStatusResponse.java                  新增
server/src/main/java/com/intelligentresume/ai/task/service/IdempotencyService.java                新增(请求指纹)
server/src/main/java/com/intelligentresume/ai/task/service/AiTaskService.java                     新增
server/src/main/java/com/intelligentresume/ai/task/controller/AiTaskController.java               新增

server/src/main/java/com/intelligentresume/ai/worker/DatabaseTaskWorker.java                     新增(@Scheduled 轮询)
server/src/main/java/com/intelligentresume/ai/worker/TaskLeaseService.java                       新增(抢占/续租/释放)
server/src/main/java/com/intelligentresume/ai/worker/TaskExecutionService.java                   新增(调 Provider + 写结果)
server/src/main/java/com/intelligentresume/ai/worker/AiTaskWorkerProperties.java                 新增(@ConfigurationProperties)

server/src/main/java/com/intelligentresume/ai/provider/AiProvider.java                           新增(接口)
server/src/main/java/com/intelligentresume/ai/provider/AiProviderRegistry.java                   新增
server/src/main/java/com/intelligentresume/ai/provider/MockAiProvider.java                       新增
server/src/main/java/com/intelligentresume/ai/provider/AiCallContext.java                        新增
server/src/main/java/com/intelligentresume/ai/provider/AiCallResult.java                         新增

server/src/main/java/com/intelligentresume/ai/ratelimit/AiQuotaService.java                      新增(每用户每任务类型日上限)
server/src/main/java/com/intelligentresume/ai/ratelimit/AiRateLimitFilter.java                    新增

server/src/test/java/com/intelligentresume/ai/consent/service/AiConsentServiceTest.java          新增
server/src/test/java/com/intelligentresume/ai/task/service/AiTaskServiceTest.java                 新增
server/src/test/java/com/intelligentresume/ai/task/service/IdempotencyServiceTest.java            新增
server/src/test/java/com/intelligentresume/ai/worker/TaskLeaseServiceTest.java                   新增
server/src/test/java/com/intelligentresume/ai/provider/MockAiProviderTest.java                   新增
server/src/test/java/com/intelligentresume/ai/controller/AiTaskControllerIT.java                 新增
server/src/test/java/com/intelligentresume/ai/worker/DatabaseTaskWorkerIT.java                   新增

web/src/api/ai.ts                                                新增
web/src/views/AiConsentView.vue                                 新增
web/src/router/index.ts                                          修改
```

> 禁止触碰:`resume/**`、`careermaterial/**`、`jobdescription/**`、`scoring/**`、`export/**`、`system/**`、`auth/**`、`common/**` 中除本卡新增之外的代码;`pdf-service/**`;`docs/01..13`;`docs/agent-tasks/**`;`db/migration/**`;`BaseEntity.java`;`application.yml`(除新增 `app.ai.*` 与 `spring.task.scheduling.*` 外)。
>
> **本卡严禁接入任何真实 AI Provider**。仅 Mock。

---

## 4. 包结构与命名

```text
server/src/main/java/com/intelligentresume/ai/
├── consent/
│   ├── controller/AiConsentController.java
│   ├── service/AiConsentService.java
│   ├── domain/{AiConsent.java, ConsentStatus.java}
│   ├── repository/AiConsentRepository.java
│   └── dto/{GrantConsentRequest.java, ConsentResponse.java}
├── task/
│   ├── controller/AiTaskController.java
│   ├── service/{AiTaskService.java, IdempotencyService.java}
│   ├── domain/{AiTask.java, AiTaskStatus.java, AiTaskType.java, ConfirmationStatus.java}
│   ├── repository/AiTaskRepository.java
│   └── dto/{CreateAiTaskRequest.java, AiTaskStatusResponse.java}
├── worker/
│   ├── DatabaseTaskWorker.java
│   ├── TaskLeaseService.java
│   ├── TaskExecutionService.java
│   └── AiTaskWorkerProperties.java
├── provider/
│   ├── AiProvider.java
│   ├── AiProviderRegistry.java
│   ├── MockAiProvider.java
│   ├── AiCallContext.java
│   └── AiCallResult.java
└── ratelimit/
    ├── AiQuotaService.java
    └── AiRateLimitFilter.java
```

---

## 5. 配置项

### 5.1 `server/pom.xml`(修改)

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
<!-- 已存在;若无则补 -->
```

### 5.2 `server/src/main/resources/application.yml`(修改)

```yaml
spring:
  task:
    scheduling:
      pool:
        size: 2
      thread-name-prefix: ai-worker-

app:
  ai:
    consent:
      policy-version: "v1.0.0"
      default-task-scopes: [JOB_GENERATION]
      default-data-categories: [resume_text, jd_text, career_material_text]
    quota:
      JOB_GENERATION: 30      # 每用户每日上限
      RESUME_OPTIMIZE: 30
      INLINE_OPTIMIZE: 60
      MATERIAL_IMPORT: 5
      ACHIEVEMENT_GUIDANCE: 10
      COMMUNICATION_GENERATE: 10
    worker:
      poll-interval-ms: 2000
      batch-size: 5
      lease-duration-sec: 60
      renew-interval-sec: 20
      max-attempts: 3
      backoff-base-sec: 5
      backoff-max-sec: 300
      task-timeout-sec: 120
    provider:
      mock:
        enabled: true
        latency-ms: 200
        # mock 可以配置固定的 success/failure 模式,用于测试不同路径
```

---

## 6. 数据库变更

无。T01 已建 `ai_consent` 与 `ai_task` 表。

---

## 7. 关键代码骨架

### 7.1 AiConsent 实体

```java
@Entity
@Table(name = "ai_consent")
public class AiConsent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "policy_version", nullable = false, length = 64)
    private String policyVersion;

    @Column(name = "provider_code", nullable = false, length = 64)
    private String providerCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "task_scopes_json", nullable = false, columnDefinition = "json")
    private List<String> taskScopesJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data_categories_json", nullable = false, columnDefinition = "json")
    private List<String> dataCategoriesJson;

    @Column(name = "notice_hash", nullable = false, length = 128)
    private String noticeHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ConsentStatus status;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    // getters / setters
}

enum ConsentStatus { GRANTED, WITHDRAWN }
```

### 7.2 AiTask 实体

```java
@Entity
@Table(name = "ai_task")
public class AiTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 32)
    private AiTaskType taskType;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "resume_version_id")
    private Long resumeVersionId;

    @Column(name = "job_description_id")
    private Long jobDescriptionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private AiTaskStatus status = AiTaskStatus.PENDING;

    @Column(name = "prompt_version", length = 64)
    private String promptVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json", columnDefinition = "json")
    private Map<String, Object> resultJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_snapshot_json", columnDefinition = "json")
    private Map<String, Object> inputSnapshotJson;

    @Column(name = "provider_code", length = 64)
    private String providerCode;

    @Column(name = "model_code", length = 128)
    private String modelCode;

    @Column(name = "consent_version", length = 64)
    private String consentVersion;

    @Column(name = "consent_id")
    private Long consentId;

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", length = 128)
    private String requestFingerprint;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount = 0;

    @Column(name = "max_attempts", nullable = false)
    private Integer maxAttempts = 3;

    @Column(name = "lease_owner", length = 128)
    private String leaseOwner;

    @Column(name = "lease_expires_at")
    private LocalDateTime leaseExpiresAt;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "provider_request_id", length = 128)
    private String providerRequestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "confirmation_status", nullable = false, length = 32)
    private ConfirmationStatus confirmationStatus = ConfirmationStatus.NOT_REQUIRED;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "result_resume_version_id")
    private Long resultResumeVersionId;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // getters / setters
}

enum AiTaskStatus { PENDING, RUNNING, SUCCESS, FAILED, CANCELLED }

enum AiTaskType {
    JOB_GENERATION,
    RESUME_OPTIMIZE,
    INLINE_OPTIMIZE,
    MATERIAL_IMPORT,
    ACHIEVEMENT_GUIDANCE,
    COMMUNICATION_GENERATE
}

enum ConfirmationStatus { NOT_REQUIRED, PENDING, CONFIRMED, REJECTED }
```

### 7.3 Repository

```java
public interface AiConsentRepository extends JpaRepository<AiConsent, Long> {
    Optional<AiConsent> findFirstByUserIdAndProviderCodeAndStatusOrderByOccurredAtDesc(
        Long userId, String providerCode, ConsentStatus status);
    List<AiConsent> findByUserIdOrderByOccurredAtDesc(Long userId);
}

public interface AiTaskRepository extends JpaRepository<AiTask, Long> {
    Optional<AiTask> findByIdAndUserId(Long id, Long userId);
    Optional<AiTask> findByUserIdAndTaskTypeAndIdempotencyKey(
        Long userId, AiTaskType taskType, String idempotencyKey);

    @Query(value = """
        SELECT * FROM ai_task
        WHERE (status = 'PENDING' AND next_attempt_at IS NULL OR next_attempt_at <= NOW())
           OR (status = 'RUNNING' AND lease_expires_at < NOW())
        ORDER BY id ASC
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<AiTask> claimableTasks(@Param("batchSize") int batchSize);

    @Modifying
    @Query(value = """
        UPDATE ai_task
        SET status = 'RUNNING',
            lease_owner = :owner,
            lease_expires_at = :leaseUntil,
            attempt_count = attempt_count + 1,
            updated_at = NOW()
        WHERE id = :id
          AND (status = 'PENDING' OR (status = 'RUNNING' AND lease_expires_at < NOW()))
        """, nativeQuery = true)
    int acquireLease(@Param("id") Long id,
                     @Param("owner") String owner,
                     @Param("leaseUntil") LocalDateTime leaseUntil);
}
```

### 7.4 AiConsentService

```java
@Service
public class AiConsentService {
    public ConsentResponse grant(GrantConsentRequest req, Long userId);
    public ConsentResponse current(Long userId);
    public void withdraw(Long userId);

    // 实现要点:
    // 1. grant:插入新事件(status=GRANTED),occurred_at=now
    // 2. withdraw:插入新事件(status=WITHDRAWN),occurred_at=now,不改写历史 GRANTED
    // 3. current:取最新事件;WITHDRAWN 也算有效事件,但创建任务时只有 GRANTED 才能用
    // 4. policy_version 必须等于 app.ai.consent.policy-version
}
```

### 7.5 AiTaskService

```java
@Service
public class AiTaskService {
    public AiTaskStatusResponse create(CreateAiTaskRequest req, String idempotencyKey, Long userId);
    public AiTaskStatusResponse get(Long id, Long userId);

    // create 实现要点:
    // 1. 必须存在 GRANTED 同意(最新事件);否则抛 BUSINESS(40101 或自定义 40302)
    // 2. 幂等键 + (userId, taskType) 查重;命中则校验 request_fingerprint,一致返回原任务;不一致抛 40901
    // 3. 输入字段最小化(只发任务所需的),写 input_snapshot_json
    // 4. 初始化 attempt_count=0, next_attempt_at=now, max_attempts=app.ai.worker.max-attempts
    // 5. 写入数据库,不直接调用 Provider
    // 6. 限流:同 user + taskType 当日创建数 ≤ quota,超限抛 42901
}
```

### 7.6 IdempotencyService

```java
@Service
public class IdempotencyService {
    public String fingerprint(Map<String, Object> requestBody);
    // 实现:SHA-256(canonical JSON(按 key 排序))[:32]
    // 输入只包含业务字段,忽略 createdAt/traceId 等波动字段
}
```

### 7.7 TaskLeaseService

```java
@Service
public class TaskLeaseService {
    public List<AiTask> claimBatch(String owner, int batchSize);   // 返回抢占成功的任务(已设置 RUNNING)
    public void renew(AiTask task, String owner);                   // 续租 lease_expires_at
    public void release(AiTask task, String owner, boolean successOrFailed);
    // release:
    //   - success: status=SUCCESS, 写 result_json, lease_owner=null, lease_expires_at=null
    //   - failed (可重试): status=PENDING, attempt_count 已 +1, next_attempt_at = now + backoff, lease 释放
    //   - failed (不可重试): status=FAILED, error_message, lease 释放
}
```

### 7.8 DatabaseTaskWorker

```java
@Component
public class DatabaseTaskWorker {

    @Value("${app.ai.worker.poll-interval-ms}") long pollIntervalMs;
    @Value("${app.ai.worker.lease-duration-sec}") long leaseSec;
    @Value("${spring.application.instance-id:local-1}") String instanceId;

    @Scheduled(fixedDelayString = "${app.ai.worker.poll-interval-ms}")
    public void poll() {
        String owner = instanceId + ":" + UUID.randomUUID();
        List<AiTask> claimed = leaseService.claimBatch(owner, batchSize);
        for (AiTask task : claimed) {
            executionService.execute(task, owner);
        }
    }
}
```

### 7.9 TaskExecutionService

```java
@Service
public class TaskExecutionService {
    public void execute(AiTask task, String owner) {
        try {
            AiCallContext ctx = buildContext(task);
            AiCallResult result = providerRegistry.route(task.getTaskType()).call(ctx);
            // 校验 result 符合 JSON Schema(具体 schema 由 T07/T08/T11 提供)
            // 成功:写入 result_json,更新 status=SUCCESS
            // 失败可重试:更新 attempt,next_attempt_at,status=PENDING
            // 失败不可重试:status=FAILED,error_message
        } catch (Exception e) {
            // 写入 error_message(脱敏),分类可重试/不可重试
        }
    }
}
```

### 7.10 AiProvider 接口与 Mock

```java
public interface AiProvider {
    String code();                       // 例如 "mock"
    boolean supports(AiTaskType type);
    AiCallResult call(AiCallContext ctx);
}

public record AiCallContext(AiTaskType type, Map<String, Object> input, long timeoutMs) {}

public record AiCallResult(boolean success, Map<String, Object> data, String providerRequestId,
                            boolean retryable, String errorMessage) {}

@Component
public class MockAiProvider implements AiProvider {
    public String code() { return "mock"; }
    public boolean supports(AiTaskType type) { return true; }   // 本卡都支持
    public AiCallResult call(AiCallContext ctx) {
        // 按 taskType 返回固定结构的 mock 结果
        // 必须包含 taskType 期望的字段(由调用方校验)
    }
}
```

### 7.11 AiQuotaService

```java
@Service
public class AiQuotaService {
    public void check(Long userId, AiTaskType type);
    // 当日同 type 已创建数 ≥ app.ai.quota.<TYPE> 时抛 42901
}
```

### 7.12 Controller 路由

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/ai/consent` | 当前有效同意 |
| POST | `/api/ai/consent` | 授权 |
| DELETE | `/api/ai/consent` | 撤回 |
| POST | `/api/ai/generate-resume-for-job` | 创建任务(本卡只创建,不实现业务逻辑) |
| GET | `/api/ai/tasks/{id}` | 查询任务状态 |
| POST | `/api/ai/tasks/{id}/confirm` | (T08 实现,本卡返回 501) |
| POST | `/api/ai/tasks/{id}/reject` | (T08 实现,本卡返回 501) |

> 本卡 `POST /api/ai/generate-resume-for-job` 的语义是「创建任务 + 同步触发一次 worker,等待结果」或「仅创建,异步查询」。MVP 推荐「仅创建 + 异步查询」,与 `02 §5.6`、`05 §7.1` 一致。
>
> 若选择「仅创建」,本卡 `POST` 立即返回 `202 Accepted` + `taskId` + `status=PENDING`。
>
> 本卡**不**写 `result_json`(留给 T07);Mock Provider 对 `JOB_GENERATION` 类型返回固定空 schema 结果,工作器跑通即可。

### 7.13 关键不变量

- **未同意不创建**:`AiTaskService.create` 第一步 `AiConsentService.current(userId)`;没有 `GRANTED` 抛 `40302`(需在 `ErrorCode` 新增,或复用 `FORBIDDEN`)
- **撤回后不创建**:即使有历史 GRANTED,最新事件是 WITHDRAWN,创建抛 `40302`
- **幂等键**:相同 `(userId, taskType, idempotencyKey)` + 相同指纹 → 返回原任务
- **冲突**:相同键 + 不同指纹 → `40901`
- **跨用户**:任务查询只返回 `userId == 当前用户` 的任务;跨用户 → `40401`(统一不暴露存在性)
- **撤回不改写历史**:`ai_task.consent_id` 指向当时的同意事件,即使撤回也保留
- **租约**:`acquireLease` 必须有 `FOR UPDATE SKIP LOCKED` 或类似原子保证;并发实例不会同时抢占同一任务
- **续租**:长任务执行中定期调用 `renew`,防止 `lease_expires_at` 过期被回收
- **超时**:任务执行超过 `task-timeout-sec` 视为可重试失败,释放租约
- **不可重试错误**:如 JSON Schema 校验失败、Prompt Injection 检测触发 → 直接 `FAILED`,不再重试

> **ErrorCode 扩展**:本卡需新增 `CONSENT_REQUIRED(40302, "AI 数据处理未授权或已撤回")` 与 `QUOTA_EXCEEDED(42902, "AI 任务配额已用完")`。在 `ErrorCode.java` 中追加,不要修改已有值。
>
> 或者本卡先复用 `FORBIDDEN(40301)` 与 `RATE_LIMITED(42901)`,在 T11 验收前再细分。

---

## 8. 前端变更

```text
web/src/api/ai.ts                            新增(grantConsent/currentConsent/withdrawConsent/getTask)
web/src/views/AiConsentView.vue              新增
web/src/router/index.ts                      修改
```

**UI 最小要求**:

- 首次进入「岗位定制生成」前跳转 `/ai-consent`,展示 policy 文本(由前端常量维护,后端 `notice_hash` 必须一致)
- 同意/撤回按钮;状态展示当前授权范围(provider_code、policy_version、task_scopes、data_categories)
- 不需要同意页的轮询

---

## 9. 测试清单

```text
server/src/test/java/com/intelligentresume/ai/consent/service/AiConsentServiceTest.java
    @DisplayName("正常路径: 授权后 current 返回 GRANTED")
    void grant_thenCurrent_granted()

    @DisplayName("正常路径: 撤回后 current 返回 WITHDRAWN")
    void withdraw_thenCurrent_withdrawn()

    @DisplayName("正常路径: 历史 GRANTED 不被撤回覆盖")
    void withdraw_keepsHistoryRecord()

server/src/test/java/com/intelligentresume/ai/task/service/AiTaskServiceTest.java
    @DisplayName("正常路径: 已同意用户创建任务返回 PENDING")
    void create_consented_returnsPending()

    @DisplayName("失败路径: 未同意用户创建任务抛 CONSENT_REQUIRED")
    void create_notConsented_throws()

    @DisplayName("失败路径: 撤回后创建任务抛 CONSENT_REQUIRED")
    void create_afterWithdraw_throws()

    @DisplayName("正常路径: 相同幂等键 + 相同指纹返回原任务")
    void create_sameKeySameFingerprint_returnsSameTask()

    @DisplayName("失败路径: 相同幂等键 + 不同指纹抛 40901")
    void create_sameKeyDifferentFingerprint_conflict()

    @DisplayName("正常路径: 配额耗尽后抛 42901")
    void create_quotaExceeded_throws()

server/src/test/java/com/intelligentresume/ai/worker/TaskLeaseServiceTest.java
    @DisplayName("正常路径: claimBatch 抢占成功任务")
    void claimBatch_acquires()

    @DisplayName("正常路径: 多实例并发抢占互不冲突(SKIP LOCKED)")
    void claimBatch_concurrent_acquiresDistinct()

    @DisplayName("正常路径: 租约过期任务可被回收")
    void claimBatch_expiredLease_recoverable()

    @DisplayName("正常路径: 续租 lease_expires_at 后移")
    void renew_extendsLease()

    @DisplayName("正常路径: 释放成功后 status=SUCCESS,lease 清空")
    void release_success_clearsLease()

server/src/test/java/com/intelligentresume/ai/provider/MockAiProviderTest.java
    @DisplayName("正常路径: 对 JOB_GENERATION 返回固定 mock 结果")
    void mockJobGeneration_returnsFixedResult()

    @DisplayName("正常路径: 包含 providerRequestId")
    void mockResult_hasProviderRequestId()

server/src/test/java/com/intelligentresume/ai/controller/AiTaskControllerIT.java
    @DisplayName("POST /api/ai/generate-resume-for-job 已同意返回 202 + taskId")
    void postCreate_consented_202()

    @DisplayName("POST 未同意返回 40302")
    void postCreate_notConsented_40302()

    @DisplayName("GET /api/ai/tasks/{id} 跨用户返回 40401")
    void getTask_crossUser_40401()

    @DisplayName("POST 同 idempotency-key 二次请求返回原 taskId")
    void postCreate_idempotent_sameFingerprint()

server/src/test/java/com/intelligentresume/ai/worker/DatabaseTaskWorkerIT.java
    @DisplayName("工作器抢占 PENDING 任务并由 Mock Provider 处理")
    void worker_claimsAndExecutes()

    @DisplayName("工作器重启后,租约过期任务回到可执行")
    void worker_restart_recoversExpiredLease()

    @DisplayName("任务执行失败可重试,attempt_count 递增,达到 max_attempts 进入 FAILED")
    void worker_retryableFailure_thenExhausted()
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

# 1. 先撤回(尚未授权)
curl -i -X DELETE http://localhost:8080/api/ai/consent -H "Authorization: Bearer $TOKEN"
# 期望: 200(code=0)或 40401(未找到同意事件);记录已撤回

# 2. 创建任务:应失败
curl -i -X POST http://localhost:8080/api/ai/generate-resume-for-job \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: idem-1" \
    -d '{"targetResumeId":1,"jobDescriptionId":1}'
# 期望: 40302

# 3. 授权
curl -X POST http://localhost:8080/api/ai/consent \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{
      "policyVersion":"v1.0.0",
      "providerCode":"mock",
      "taskScopes":["JOB_GENERATION"],
      "dataCategories":["resume_text","jd_text","career_material_text"],
      "noticeHash":"<固定值,例如 sha256(\"notice-v1\")>"
    }'

# 4. 创建任务
curl -X POST http://localhost:8080/api/ai/generate-resume-for-job \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: idem-1" \
    -d '{"targetResumeId":1,"jobDescriptionId":1}'
# 期望: 202 + taskId, status=PENDING

# 5. 轮询
sleep 4
curl http://localhost:8080/api/ai/tasks/$TASK_ID -H "Authorization: Bearer $TOKEN"
# 期望: status=SUCCESS, result_json 为 mock 返回(本卡阶段是空结构)

# 6. 重复 idempotent 请求:返回原 taskId
curl -X POST http://localhost:8080/api/ai/generate-resume-for-job \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: idem-1" \
    -d '{"targetResumeId":1,"jobDescriptionId":1}'
```

---

## 11. 停止条件

补充 `T00 §6`:

- [ ] 需要真实调用 LLM
- [ ] 需要在 POST 创建任务时同步等待结果(违反 MVP 异步契约)
- [ ] 需要修改 `ErrorCode` 已有 code 值
- [ ] 工作器不持有租约即可执行
- [ ] `ai_consent` 撤回时改写历史记录(违反 `04 §3.1.2`)
- [ ] 跨用户查询任务允许返回 40301(必须返回 40401)

---

## 12. 完成报告

```text
任务 ID:T06-AI 同意与任务框架
状态:DONE / BLOCKED
修改文件: <按 §3 实际改动列出>

未修改的禁止范围:
  - 真实 LLM 调用:未接入
  - 业务逻辑(JOB_GENERATION 真实生成):未实现
  - 评分/PDF/简历内容生成:未触碰
  - ai_consent 历史改写:未发生
  - ErrorCode 已有值:未修改(只追加)

契约来源:
  - 02 §7.3(AI 数据处理要求)
  - 03 §9.5(数据库任务工作器)/§9.6(不可信输入)
  - 04 §3.1.2(ai_consent)/§3.8(ai_task)
  - 05 §2.6(同意接口)/§7.5(任务查询)
  - 07 §5.6(AI 测试)
  - 12 §3(不可违反规则)
  - 13 §3(全局不变量)

先失败的测试或现状基线:
  - 基线:MockAiProvider 不存在,JOB_GENERATION 调用会 NoSuchBean
  - 或:工作器无租约,任务永远 PENDING

验证命令与结果:
  - mvn test -> Tests run: N, Failures: 0
  - 集成测试 同意 → 创建 → 轮询 → SUCCESS -> 4 步通过
  - 集成测试 撤回后创建失败 -> 通过

权限/归属/幂等/失败场景证据:
  - 必测 1(未同意):通过
  - 必测 2(撤回后):通过
  - 必测 3(幂等键同):通过
  - 必测 4(幂等键冲突):通过
  - 必测 5(工作器重启恢复):通过
  - 必测 6(租约过期):通过
  - 必测 7(最大重试):通过
  - 必测 8(跨用户):通过

文档是否需要同步: 否,接口路径与 05 §2.6/§7.5 一致;Mock Provider 行为后续 T07/T08 进一步细化

剩余风险:
  - 单实例测试未覆盖真实多实例并发;M4 才补
  - Mock 返回结果与真实 LLM 行为差异;T07/T08 应针对 schema 校验而非具体内容
  - 工作器未启用 Prometheus metrics(M4)
```