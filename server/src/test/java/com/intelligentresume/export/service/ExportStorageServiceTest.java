package com.intelligentresume.export.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExportStorageService 单元测试。
 */
class ExportStorageServiceTest {

    @TempDir
    Path tempDir;

    private ExportStorageService service;

    @BeforeEach
    void setUp() {
        service = new ExportStorageService(tempDir.toString());
    }

    @Test
    @DisplayName("正常路径: 存储后 read 返回相同字节")
    void storeAndRead() {
        byte[] content = "Hello PDF content".getBytes();
        ExportStorageService.StoredFile stored = service.store(content, "pdf");

        assertNotNull(stored.storageKey());
        assertTrue(stored.storageKey().endsWith(".pdf"));
        assertEquals(content.length, stored.size());
        assertNotNull(stored.checksumSha256());
        assertEquals(64, stored.checksumSha256().length()); // SHA-256 hex = 64 chars

        byte[] readBack = service.read(stored.storageKey());
        assertArrayEquals(content, readBack);
    }

    @Test
    @DisplayName("正常路径: storageKey 随机且不含 userId")
    void storageKey_randomNoUserId() {
        byte[] content = "test".getBytes();
        ExportStorageService.StoredFile stored1 = service.store(content, "pdf");
        ExportStorageService.StoredFile stored2 = service.store(content, "pdf");

        // 两次存储的 key 不同
        assertNotEquals(stored1.storageKey(), stored2.storageKey());
        // 不含业务字段
        assertFalse(stored1.storageKey().contains("user"));
        assertFalse(stored1.storageKey().contains("resume"));
    }

    @Test
    @DisplayName("正常路径: delete 删除文件后 read 返回 null")
    void deleteRemovesFile() {
        byte[] content = "to be deleted".getBytes();
        ExportStorageService.StoredFile stored = service.store(content, "pdf");

        assertTrue(service.delete(stored.storageKey()));
        assertNull(service.read(stored.storageKey()));
    }

    @Test
    @DisplayName("正常路径: read 不存在的 key 返回 null")
    void readNonExistent_returnsNull() {
        assertNull(service.read("non-existent-key.pdf"));
    }

    @Test
    @DisplayName("正常路径: checksum 一致性")
    void checksumConsistent() {
        byte[] content = "same content".getBytes();
        ExportStorageService.StoredFile stored1 = service.store(content, "pdf");
        ExportStorageService.StoredFile stored2 = service.store(content, "pdf");

        // 相同内容的 checksum 相同
        assertEquals(stored1.checksumSha256(), stored2.checksumSha256());
    }
}
