package com.intelligentresume.imports.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.imports.dto.ResumeImportResponse;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 简历导入服务单元测试。
 *
 * <p>覆盖 TXT/PDF/DOCX 三种格式文本抽取、5MB 上限校验、
 * 扩展名与内容类型匹配校验、以及邮箱/电话 PII 提取正则。
 */
class ResumeImportServiceTest {

    /** 与业务上限一致：5MB */
    private static final long MAX_BYTES = 5 * 1024 * 1024;

    private final ResumeImportService service = new ResumeImportService(MAX_BYTES);

    // ---- 三种格式抽取 ----

    @Test
    @DisplayName("TXT：抽取文本并归一化姓名/邮箱/电话")
    void parsesTxt_extractsAndNormalizes() {
        MockMultipartFile file = new MockMultipartFile("file", "alice.txt", "text/plain",
                "Alice Chen\nalice@example.com\n13800138000\nJava platform engineer"
                        .getBytes(StandardCharsets.UTF_8));

        ResumeImportResponse response = service.parse(file);

        assertEquals("alice.txt", response.fileName());
        assertEquals("text/plain", response.mediaType());
        assertTrue(response.extractedText().contains("Java platform engineer"));
        assertEquals("Alice Chen", basics(response).get("name"));
        assertEquals("alice@example.com", basics(response).get("email"));
        assertEquals("13800138000", basics(response).get("phone"));
        assertFalse(response.originalFileStored());
    }

    @Test
    @DisplayName("PDF：PDFBox 抽取文本")
    void parsesPdf_extractsText() throws Exception {
        byte[] pdf = pdfBytes("Bob Zhang", "bob@example.com", "Senior Data Engineer");
        MockMultipartFile file = new MockMultipartFile("file", "bob.pdf", "application/pdf", pdf);

        ResumeImportResponse response = service.parse(file);

        assertEquals("bob.pdf", response.fileName());
        assertEquals("application/pdf", response.mediaType());
        assertTrue(response.extractedText().contains("Senior Data Engineer"));
        assertEquals("Bob Zhang", basics(response).get("name"));
    }

    @Test
    @DisplayName("DOCX：POI 抽取文本")
    void parsesDocx_extractsText() throws Exception {
        byte[] docx = docxBytes("Carol Wang", "carol@example.com", "Product Manager");
        MockMultipartFile file = new MockMultipartFile("file", "carol.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx);

        ResumeImportResponse response = service.parse(file);

        assertEquals("carol.docx", response.fileName());
        assertTrue(response.extractedText().contains("Product Manager"));
        assertEquals("Carol Wang", basics(response).get("name"));
    }

    // ---- 大小限制 ----

    @Test
    @DisplayName("超过 5MB 拒绝")
    void rejectsOversizedFile() {
        byte[] large = new byte[6 * 1024 * 1024];
        MockMultipartFile file = new MockMultipartFile("file", "large.txt", "text/plain", large);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.parse(file));

        assertEquals(ErrorCode.VALIDATION, ex.getErrorCode());
        assertEquals("文件不能超过 5 MB", ex.getMessage());
    }

    // ---- 类型校验 ----

    @Test
    @DisplayName("扩展名与内容类型不匹配拒绝")
    void rejectsExtensionAndContentTypeMismatch() {
        MockMultipartFile file = new MockMultipartFile("file", "resume.pdf", "text/plain",
                "not a pdf".getBytes(StandardCharsets.UTF_8));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.parse(file));

        assertEquals(ErrorCode.VALIDATION, ex.getErrorCode());
        assertEquals("文件扩展名与内容类型不匹配", ex.getMessage());
    }

    @Test
    @DisplayName("不支持的文件类型拒绝")
    void rejectsUnsupportedExtension() {
        MockMultipartFile file = new MockMultipartFile("file", "payload.exe", "application/octet-stream",
                new byte[]{1, 2, 3});

        BusinessException ex = assertThrows(BusinessException.class, () -> service.parse(file));

        assertEquals(ErrorCode.VALIDATION, ex.getErrorCode());
        assertEquals("仅支持 TXT、PDF 或 DOCX 文件", ex.getMessage());
    }

    @Test
    @DisplayName("空文件拒绝")
    void rejectsEmptyFile() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.parse(file));

        assertEquals(ErrorCode.VALIDATION, ex.getErrorCode());
        assertEquals("文件不能为空", ex.getMessage());
    }

    @Test
    @DisplayName("损坏 PDF：解析失败包装为业务错误")
    void rejectsCorruptPdf() {
        MockMultipartFile file = new MockMultipartFile("file", "corrupt.pdf", "application/pdf",
                new byte[]{0x25, 0x50, 0x44, 0x46, 0x00, 0x00, 0x00, 0x00});

        BusinessException ex = assertThrows(BusinessException.class, () -> service.parse(file));

        assertEquals(ErrorCode.VALIDATION, ex.getErrorCode());
        assertEquals("文件内容无法解析", ex.getMessage());
    }

    // ---- PII 提取正则 ----

    @Test
    @DisplayName("PII 正则：从正文提取邮箱与电话")
    void extractsPiiFromBody() {
        MockMultipartFile file = new MockMultipartFile("file", "pii.txt", "text/plain",
                "Diana\n联系方式: diana.wang@example.com / 13912345678\n后端工程师"
                        .getBytes(StandardCharsets.UTF_8));

        ResumeImportResponse response = service.parse(file);

        assertEquals("diana.wang@example.com", basics(response).get("email"));
        assertEquals("13912345678", basics(response).get("phone"));
    }

    @Test
    @DisplayName("PII 正则：无效手机号与缺失邮箱不提取")
    void skipsInvalidPii() {
        MockMultipartFile file = new MockMultipartFile("file", "no-pii.txt", "text/plain",
                "Evan\n电话 12345678901（第二位非 3-9，不应匹配）\n邮箱 not-an-email"
                        .getBytes(StandardCharsets.UTF_8));

        ResumeImportResponse response = service.parse(file);

        assertNull(basics(response).get("email"));
        assertNull(basics(response).get("phone"));
    }

    @Test
    @DisplayName("PII 正则：86 前缀手机号可识别")
    void extractsPhoneWithCountryCode() {
        MockMultipartFile file = new MockMultipartFile("file", "cn-phone.txt", "text/plain",
                "Frank\n+86 13812345678\n架构师".getBytes(StandardCharsets.UTF_8));

        ResumeImportResponse response = service.parse(file);

        assertEquals("+86 13812345678", basics(response).get("phone"));
    }

    // ---- 帮助方法 ----

    @SuppressWarnings("unchecked")
    private Map<String, Object> basics(ResumeImportResponse response) {
        return (Map<String, Object>) response.normalizedResumeInput().get("basics");
    }

    private byte[] pdfBytes(String... lines) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(PDType1Font.HELVETICA, 12);
                cs.beginText();
                cs.newLineAtOffset(50, 700);
                for (String line : lines) {
                    cs.showText(line);
                    cs.newLineAtOffset(0, -20);
                }
                cs.endText();
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private byte[] docxBytes(String... lines) throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            for (String line : lines) {
                XWPFParagraph paragraph = doc.createParagraph();
                XWPFRun run = paragraph.createRun();
                run.setText(line);
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.write(baos);
            return baos.toByteArray();
        }
    }
}
