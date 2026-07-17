# TEMPLATE — Tnn 操作手册骨架模板

> 本文件是 `docs/agent-tasks/Tnn-<名称>.md` 的固定 12 节骨架。
>
> 当需要新增 T12+ 任务卡时,复制本模板,按节填充,然后提交评审。
>
> **不得删除、合并、重排章节顺序**。`README §4` 要求所有 Tnn 必须保持 12 节固定结构。

---

## 1. 任务卡摘要

> 从 `docs/13-MVP实施契约与任务卡.md` §5 本卡直接复述,代理不得跳读。

```text
任务名称:Tnn-<名称>
目标: <从 13 §5 本卡原文>
当前阶段与优先级: <从 13 §5 本卡原文>
来源文档: <02..05 章节号列表,每条一行>
允许范围: <从 13 §5 本卡原文>
禁止: <从 13 §5 本卡原文>
完成定义: <从 13 §5 本卡原文>
```

---

## 2. 本卡依赖

> 列出前置 Tnn 与外部依赖(例如 MySQL 已启动、`.env` 已复制)。

### 2.1 前置 Tnn

- [ ] Tnn-x.x 已完成并验证通过
- [ ] Tnn-y.y 已完成并验证通过

### 2.2 外部依赖

- [ ] MySQL 已启动(`docker compose up -d mysql`)
- [ ] `.env` 已创建(`Copy-Item .env.example .env`)
- [ ] 后端默认端口 8080 未占用
- [ ] PDF 服务默认端口 3001 未占用(仅本卡涉及 PDF)

---

## 3. 目标文件清单

> 精确到文件路径。每条标明「新增 / 修改」。
>
> **清单外的文件不得触碰**。

```text
server/pom.xml                                                          修改
server/src/main/resources/application.yml                               修改
server/src/main/resources/db/migration/Vn__<name>.sql                   新增
server/src/main/java/com/intelligentresume/<module>/controller/Xxx.java 新增
server/src/main/java/com/intelligentresume/<module>/service/Xxx.java    新增
...
server/src/test/java/com/intelligentresume/<module>/XxxTest.java        新增
```

---

## 4. 包结构与命名

> ASCII 文件树,符合 `10 §5.1` 推荐分层。模块按业务命名,不按技术层全局堆积。

```text
server/src/main/java/com/intelligentresume/<module>/
├── controller/
├── service/
├── domain/
├── repository/
├── dto/
└── (本卡特有,如 jwt/ ratelimit/ validator/ ...)

server/src/test/java/com/intelligentresume/<module>/
├── controller/
└── service/
```

命名约定(`10 §5.2`):

- 类名:大驼峰
- 方法名:小驼峰
- 常量名:全大写下划线
- 表名:小写下划线
- 优先使用 `record` 而非 Lombok

---

## 5. 配置项

> 只列**本卡**要补/改的字段差异,不写完整配置文件。

### 5.1 `server/pom.xml`(如需)

```xml
<dependency>
    <groupId>...</groupId>
    <artifactId>...</artifactId>
</dependency>
```

### 5.2 `server/src/main/resources/application.yml`(如需)

```yaml
<key>: <value>
```

### 5.3 `web/package.json`(如需)

```json
{
  "dependencies": {
    "<package>": "<version>"
  }
}
```

### 5.4 `pdf-service/package.json`(如需)

```json
{
  "dependencies": {
    "<package>": "<version>"
  }
}
```

---

## 6. 数据库变更(本卡如有)

### 6.1 迁移文件

```text
server/src/main/resources/db/migration/Vn__<name>.sql
```

> 命名规范:`V<序号>__<下划线分隔的简短名称>.sql`,序号从 1 开始,不可跳跃。

### 6.2 DDL 摘要

> 与 `docs/04-数据库设计说明书.md` §3 对应。每张表列字段、类型、约束、索引。

```sql
CREATE TABLE <table_name> (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    -- ... 字段 ...
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_<table>_<col> (<col>),
    KEY idx_<table>_<col> (<col>)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### 6.3 索引与约束

- 字符集:`utf8mb4`
- 排序规则:`utf8mb4_unicode_ci`
- 引擎:`InnoDB`
- 主键策略:`BIGINT AUTO_INCREMENT`
- 软删字段:`deleted_at DATETIME NULL`(与 `BaseEntity` 配合)

---

## 7. 关键代码骨架

> **只给骨架和字段,不写完整业务实现**。代理不得直接复制粘贴作为最终代码。
>
> 命名与既有约定一致(`ApiResponse` / `BusinessException` / `ErrorCode`)。

### 7.1 实体 / 枚举

```java
package com.intelligentresume.<module>.domain;

import com.intelligentresume.common.domain.BaseEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "<table_name>")
public class XxxEntity extends BaseEntity {

    @Column(name = "<col>", nullable = false, length = 64)
    private String xxx;

    // ... 其他字段 ...
}
```

### 7.2 Repository

```java
package com.intelligentresume.<module>.repository;

public interface XxxRepository extends JpaRepository<XxxEntity, Long> {
    Optional<XxxEntity> findByIdAndUserId(Long id, Long userId);
}
```

### 7.3 Service

```java
package com.intelligentresume.<module>.service;

@Service
public class XxxService {

    public XxxResponse doXxx(XxxRequest request, Long currentUserId) {
        // 1. 校验资源归属
        // 2. 执行业务逻辑
        // 3. 持久化
        // 4. 返回结果
    }
}
```

### 7.4 Controller

```java
package com.intelligentresume.<module>.controller;

@RestController
@RequestMapping("/api/<module>")
public class XxxController {

    @PostMapping
    public ApiResponse<XxxResponse> create(@Valid @RequestBody XxxRequest req, ...) {
        // 调用 service,捕获 BusinessException,由 GlobalExceptionHandler 统一转换
    }
}
```

### 7.5 DTO 字段表

> 与 `docs/05-接口设计说明书.md` 对应章节严格一致。

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `xxx` | string | 是 | 含义 |

---

## 8. 前端变更(本卡如有)

### 8.1 新增 / 修改文件

```text
web/src/api/<module>.ts           新增
web/src/stores/<name>.ts          新增
web/src/views/<Name>View.vue      新增
web/src/router/index.ts           修改(添加路由)
```

### 8.2 Pinia store(如需)

```typescript
import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useXxxStore = defineStore('xxx', () => {
    const state = ref<XxxState | null>(null)
    async function loadXxx() {
        // 调用 web/src/api/<module>.ts 中的函数
    }
    return { state, loadXxx }
})
```

### 8.3 视图状态契约(13 §6)

每个异步页面必须实现:初始、加载、空数据、PENDING、RUNNING、SUCCESS、FAILED、CANCELLED、无权限、网络断开。

轮询采用 1s、2s、4s、5s 封顶退避;任务 ID 持久化,重载后恢复。

---

## 9. 测试清单

> 每条 `@DisplayName` 对应 `13 §5` 本卡「必测」中的一个场景。
>
> 至少覆盖:正常路径、边界/空值路径、权限/失败路径。

```text
src/test/java/.../XxxServiceTest.java
    @DisplayName("正常路径: 用户注册成功")
    void register_success() { }

    @DisplayName("失败路径: 重复用户名注册失败")
    void register_duplicateUsername_throwsBusinessException() { }

    @DisplayName("边界路径: 空请求体返回 40001")
    void register_emptyRequest_returnsValidationError() { }
```

---

## 10. 验证命令

> 从 `13` 复述 + 本卡特定命令。按顺序执行,任一失败先修复。

```bash
# 进入后端目录
cd server

# 编译
mvn -q -DskipTests compile

# 单元 + 集成测试
mvn test

# 启动并验证
mvn spring-boot:run
# 另开终端
curl http://localhost:8080/api/system/health
```

(本卡特定命令,如适用)

```bash
# 前端构建
cd web && npm run build

# PDF 服务语法检查
cd pdf-service && npm run check
```

---

## 11. 停止条件(BLOCKED 触发)

> 本卡特有的 BLOCKED 触发,补充 `T00 §6` 的通用触发。

- [ ] 来源文档相互矛盾
- [ ] 需要新增未在 §3 列出的文件
- [ ] 无法确定当前用户的资源归属规则
- [ ] 需要 `13 §2` 固定技术决策之外的依赖
- [ ] 测试失败且影响其他模块
- [ ] AI 调用结果无法映射到任何资料(若本卡涉及 AI)

报告格式见 `T00 §6`。

---

## 12. 完成报告

按 `T00 §5` 的模板逐项填写,缺任何一项不得报告 DONE。

```text
任务 ID:Tnn-<名称>
状态:DONE / BLOCKED
修改文件: <精确路径列表>
未修改的禁止范围: <逐条确认 §3.1..§3.3 未触发>
契约来源: <本卡「来源」节列出>
先失败的测试或现状基线: <命令与证据>
验证命令与结果: <按 §10 顺序逐条贴>
权限/归属/幂等/失败场景证据: <§9 每个场景一行证据>
文档是否需要同步: 是 / 否,原因: 
剩余风险: 
```