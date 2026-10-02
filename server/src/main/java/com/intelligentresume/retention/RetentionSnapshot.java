package com.intelligentresume.retention;

/**
 * B 档「最小不可编辑快照」的内容口径（决策 D2 阶段 2）。
 *
 * <p>B 档针对**被引用**的超期软删行：不能物理删除（会撞外键、破坏历史可读性），
 * 但也不该继续留完整 PII。做法是把承载正文的列替换成**常量快照标记**，其余元数据列原样保留
 * （审计需要的是「这条记录存在过、属于谁、何时被删」，那些都在元数据里）。
 *
 * <p><b>为什么是常量而不是「保留摘要前 200 字」</b>：
 * <ol>
 *   <li>那需要在同一条语句里从**即将被清空**的字段派生内容，第二次执行时源已为空 ⇒ 结果不同
 *       （**非幂等**），而清扫作业会反复跑；</li>
 *   <li>摘要是 AI 生成的正文，本身可能就是 PII 载体。</li>
 * </ol>
 *
 * <p><b>为什么不用「只保留顶层键名」</b>（方案 §4 的初版口径，实施时否决）—— 第六十八批实测：
 * 「取键名、值置 null」需要把**动态生成的 JSON 文本**写回 JSON 列，而这一步在两个数据库上
 * **语义不同**：
 * <ul>
 *   <li>MySQL：{@code CAST(? AS JSON)} 会把文本解析成 JSON 对象 ✓；</li>
 *   <li>H2：{@code CAST('{"a":1}' AS JSON)} 得到的却是 JSON **字符串值** {@code "{\"a\":1}"}
 *       （读取回来带引号、不是对象），需要 {@code '...' FORMAT JSON} 才是对象。</li>
 * </ul>
 * 为一条审计辅助信息引入方言分支（生产 SQL 与测试 SQL 不同）不划算；键集本身也可由
 * {@code material_type} 与资料契约推导。故统一为常量标记。
 */
final class RetentionSnapshot {

    /** 已快照化的标记内容：只留「正文已被清空」这一事实（常量 ⇒ 重复执行结果一致）。 */
    static final String PURGED_JSON = "{\"__purged\":true}";

    /**
     * 供 SQL 直接内联的 JSON 表达式 —— 两个数据库都有 {@code JSON_OBJECT}，
     * 因此不需要绑定字符串参数，也就绕开了上面那条方言差异。
     */
    static final String PURGED_JSON_EXPRESSION = "JSON_OBJECT('__purged', TRUE)";

    private RetentionSnapshot() {
    }
}
