package com.intelligentresume.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SourceText} 的自测：注释必须被剥掉，而**字符串字面量必须原样保留**。
 *
 * <p>第二条是重点：不少"消费点"本身就是字符串里的占位符（`"${app.x.y:1}"`），
 * 而朴素的 `indexOf("//")` 剥法会把 `"http://host"` 截断 —— 既制造假红，
 * 也破坏了"保留字符串"的初衷。故此处把这两个方向都钉死。
 */
class SourceTextTest {

    @Test
    @DisplayName("C 风格：行注释与块注释被剥掉（键名不再命中）")
    void stripsCStyleComments() {
        String source = String.join("\n",
                "class A {",
                "    // 这里提到 app.job.jd-text.min-length 但不消费它",
                "    /**",
                "     * javadoc 也提到 `app.job.jd-text.min-length`",
                "     */",
                "    int x = 1;",
                "} // 尾随注释 app.job.jd-text.min-length");

        String stripped = SourceText.stripComments(source, "A.java");

        assertFalse(stripped.contains("app.job.jd-text.min-length"),
                "注释（行/块/javadoc/尾随）里的键名都不得残留：\n" + stripped);
        assertTrue(stripped.contains("class A {"), "代码本体必须保留");
        assertTrue(stripped.contains("int x = 1;"), "代码本体必须保留");
        assertEquals(source.length(), stripped.length(),
                "必须等长抹白（保留偏移）：距离窗口式判据（如「300 字符内」）依赖长度不变");
        assertEquals(source.chars().filter(c -> c == '\n').count(),
                stripped.chars().filter(c -> c == '\n').count(), "换行必须保留（行号/行首判据依赖它）");
    }

    @Test
    @DisplayName("字符串字面量原样保留：\"http://…\" 不会被 // 截断")
    void keepsStringLiterals() {
        String source = String.join("\n",
                "String a = \"http://pdf-service:3001\";",
                "String b = \"a // not a comment\";",
                "String c = \"escaped \\\" quote\";",
                "String d = \"${app.retention.purge.interval-ms:21600000}\";");

        String stripped = SourceText.stripComments(source, "S.java");

        assertTrue(stripped.contains("http://pdf-service:3001"), "URL 不得被 // 截断");
        assertTrue(stripped.contains("a // not a comment"), "字符串里的 // 不得当注释");
        assertTrue(stripped.contains("escaped \\\" quote"), "转义引号不得中断字面量");
        assertTrue(stripped.contains("${app.retention.purge.interval-ms:21600000}"),
                "占位符（真实消费点的一种）必须保留，否则会制造假红");
    }

    @Test
    @DisplayName("Java 文本块（\"\"\"）内部内容保留")
    void keepsTextBlocks() {
        String source = String.join("\n",
                "String sql = \"\"\"",
                "        SELECT 1 FROM resume_version rv -- 这不是 SQL 注释行",
                "        \"\"\";",
                "// 块注释里提到 resume_version");

        String stripped = SourceText.stripComments(source, "T.java");

        assertTrue(stripped.contains("SELECT 1 FROM resume_version rv"));
        assertFalse(stripped.contains("块注释里提到"), "块注释仍应被剥掉");
    }

    @Test
    @DisplayName("# 风格（yml/conf/env）：注释行被剥，引号内的 # 保留")
    void stripsHashStyle() {
        String source = String.join("\n",
                "# app.mock.enabled 已移除（此注释不应算作消费点）",
                "app:",
                "  name: \"a#b\"",
                "  other: plain # 尾随注释");

        String stripped = SourceText.stripComments(source, "application.yml");

        assertFalse(stripped.contains("app.mock.enabled"), "yml 注释里的键名不得残留");
        assertTrue(stripped.contains("\"a#b\""), "引号内的 # 不是注释");
        assertFalse(stripped.contains("尾随注释"), "行内 # 之后应被剥掉");
    }

    @Test
    @DisplayName("SQL 风格（--）：注释行被剥，字符串内的 -- 保留")
    void stripsSqlStyle() {
        String source = String.join("\n",
                "-- 迁移注释：CREATE TABLE resume_version",
                "CREATE TABLE resume_version (",
                "    note VARCHAR(32) DEFAULT 'a--b'",
                ");");

        String stripped = SourceText.stripComments(source, "V9__x.sql");

        assertFalse(stripped.contains("迁移注释"));
        assertTrue(stripped.contains("CREATE TABLE resume_version"), "真实的建表语句必须保留");
        assertTrue(stripped.contains("'a--b'"), "字符串里的 -- 不是注释");
    }

    @Test
    @DisplayName("文档与未知扩展名不剥（散文里的 # 是正文）")
    void leavesDocsAndUnknownTypesAlone() {
        String doc = "## 7.1 生命周期\n\n| 资源 | 说明 |\n";
        assertEquals(doc, SourceText.stripComments(doc, "04-数据库设计说明书.md"));
        String json = "{\"a\": \"#not-a-comment\"}";
        assertEquals(json, SourceText.stripComments(json, "x.json"));
    }
}
