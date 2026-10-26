package com.intelligentresume.ai.provider;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 联系方式脱敏（R32：默认排除联系方式）。
 *
 * <p>关键在于**只脱联系方式**：URL、版本号、金额、正文都不能被误伤 ——
 * 过度脱敏会让生成质量明显下降，而用户不会意识到是脱敏造成的。
 */
class ContactRedactorTest {

    @Test
    void redactsEmailAndMobile() {
        String text = "联系我：alice@example.com / 13800138000";
        String redacted = ContactRedactor.redactText(text);
        assertTrue(redacted.contains("[EMAIL]"));
        assertTrue(redacted.contains("[PHONE]"));
        assertEquals(false, redacted.contains("alice@example.com"));
        assertEquals(false, redacted.contains("13800138000"));
    }

    @Test
    void keepsUrlsVersionsAndMoney() {
        String text = "项目地址 https://github.com/alice/project ，Spring Boot 3.3.2，期望薪资 25000 元。";
        assertEquals(text, ContactRedactor.redactText(text),
                "URL / 版本号 / 金额都不是联系方式，不能脱敏");
    }

    @Test
    void countsWhatWouldBeReplaced() {
        Map<String, String> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", "a@b.com 与 c@d.com，电话 13900001111");
        List<ContactRedactor.Redaction> summary = ContactRedactor.summarize(List.of(message));

        Map<String, Integer> byCategory = new LinkedHashMap<>();
        summary.forEach(item -> byCategory.put(item.category(), item.count()));
        assertEquals(2, byCategory.get("EMAIL"));
        assertEquals(1, byCategory.get("PHONE"));
    }

    @Test
    void doesNotDoubleCountPhoneDigitsInsideEmail() {
        Map<String, String> message = new LinkedHashMap<>();
        message.put("role", "user");
        // 邮箱本地部分以 1 开头的 11 位数字：不能被当成手机号额外计一次
        message.put("content", "13800138000@example.com");
        List<ContactRedactor.Redaction> summary = ContactRedactor.summarize(List.of(message));
        Map<String, Integer> byCategory = new LinkedHashMap<>();
        summary.forEach(item -> byCategory.put(item.category(), item.count()));
        assertEquals(1, byCategory.get("EMAIL"));
        assertEquals(null, byCategory.get("PHONE"));
    }

    @Test
    void leavesNonNullFieldsUntouched() {
        Map<String, String> message = new LinkedHashMap<>();
        message.put("role", "system");
        List<Map<String, String>> redacted = ContactRedactor.redact(List.of(message));
        assertEquals("system", redacted.get(0).get("role"));
        assertEquals(1, redacted.get(0).size());
    }
}
