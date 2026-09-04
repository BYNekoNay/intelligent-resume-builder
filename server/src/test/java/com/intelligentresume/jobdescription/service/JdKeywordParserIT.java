package com.intelligentresume.jobdescription.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that the production YAML keyword dictionary reaches the parser bean. */
@SpringBootTest
@ActiveProfiles("test")
class JdKeywordParserIT {

    @Autowired
    private JdKeywordParser parser;

    @Test
    void configuredDictionaryIncludesExtendedChineseAndStreamingKeywords() {
        var result = parser.parse("熟悉 Java、Spring Boot、Kafka、微服务、高并发和 MySQL");

        assertTrue(result.keywords().contains("Kafka"), result.keywords()::toString);
        assertTrue(result.keywords().contains("微服务"), result.keywords()::toString);
        assertTrue(result.keywords().contains("高并发"), result.keywords()::toString);
    }
}
