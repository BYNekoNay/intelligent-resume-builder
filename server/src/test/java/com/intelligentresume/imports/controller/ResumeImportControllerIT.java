package com.intelligentresume.imports.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResumeImportControllerIT {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    private static String token;

    @BeforeAll
    static void placeholder() { }

    @Test
    void parsesTxtMultipartWithoutStoringOriginal() throws Exception {
        ensureToken();
        MockMultipartFile file = new MockMultipartFile("file", "alice.txt", "text/plain",
                "Alice Chen\nalice@example.com\n13800138000\nJava platform engineer".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/resume-imports/parse").file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fileName").value("alice.txt"))
                .andExpect(jsonPath("$.data.mediaType").value("text/plain"))
                .andExpect(jsonPath("$.data.extractedText").value(org.hamcrest.Matchers.containsString("Java platform engineer")))
                .andExpect(jsonPath("$.data.normalizedResumeInput.basics.name").value("Alice Chen"))
                .andExpect(jsonPath("$.data.normalizedResumeInput.basics.email").value("alice@example.com"))
                .andExpect(jsonPath("$.data.normalizedResumeInput.basics.phone").value("13800138000"))
                .andExpect(jsonPath("$.data.originalFileStored").value(false));
    }

    @Test
    void parsesPdfMultipart() throws Exception {
        ensureToken();
        byte[] pdfBytes;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(PDType1Font.HELVETICA, 12);
                cs.beginText();
                cs.newLineAtOffset(50, 700);
                cs.showText("Bob Zhang");
                cs.newLineAtOffset(0, -20);
                cs.showText("bob@example.com");
                cs.newLineAtOffset(0, -20);
                cs.showText("13900139000");
                cs.newLineAtOffset(0, -20);
                cs.showText("Senior Data Engineer");
                cs.endText();
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            pdfBytes = baos.toByteArray();
        }
        MockMultipartFile file = new MockMultipartFile("file", "bob.pdf", "application/pdf", pdfBytes);
        mockMvc.perform(multipart("/api/resume-imports/parse").file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fileName").value("bob.pdf"))
                .andExpect(jsonPath("$.data.mediaType").value("application/pdf"))
                .andExpect(jsonPath("$.data.extractedText").value(org.hamcrest.Matchers.containsString("Senior Data Engineer")))
                .andExpect(jsonPath("$.data.normalizedResumeInput.basics.name").value("Bob Zhang"))
                .andExpect(jsonPath("$.data.normalizedResumeInput.basics.email").value("bob@example.com"))
                .andExpect(jsonPath("$.data.normalizedResumeInput.basics.phone").value("13900139000"))
                .andExpect(jsonPath("$.data.originalFileStored").value(false));
    }

    @Test
    void parsesDocxMultipart() throws Exception {
        ensureToken();
        byte[] docxBytes;
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph p1 = doc.createParagraph();
            XWPFRun r1 = p1.createRun();
            r1.setText("Carol Wang");
            XWPFParagraph p2 = doc.createParagraph();
            XWPFRun r2 = p2.createRun();
            r2.setText("carol@example.com");
            XWPFParagraph p3 = doc.createParagraph();
            XWPFRun r3 = p3.createRun();
            r3.setText("13700137000");
            XWPFParagraph p4 = doc.createParagraph();
            XWPFRun r4 = p4.createRun();
            r4.setText("Product Manager");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.write(baos);
            docxBytes = baos.toByteArray();
        }
        MockMultipartFile file = new MockMultipartFile("file", "carol.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docxBytes);
        mockMvc.perform(multipart("/api/resume-imports/parse").file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fileName").value("carol.docx"))
                .andExpect(jsonPath("$.data.extractedText").value(org.hamcrest.Matchers.containsString("Product Manager")))
                .andExpect(jsonPath("$.data.normalizedResumeInput.basics.name").value("Carol Wang"))
                .andExpect(jsonPath("$.data.normalizedResumeInput.basics.email").value("carol@example.com"))
                .andExpect(jsonPath("$.data.normalizedResumeInput.basics.phone").value("13700137000"))
                .andExpect(jsonPath("$.data.originalFileStored").value(false));
    }

    @Test
    void rejectsCorruptPdf() throws Exception {
        ensureToken();
        MockMultipartFile file = new MockMultipartFile("file", "corrupt.pdf", "application/pdf",
                new byte[]{0x25, 0x50, 0x44, 0x46, 0x00, 0x00, 0x00, 0x00});
        mockMvc.perform(multipart("/api/resume-imports/parse").file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void rejectsCorruptDocx() throws Exception {
        ensureToken();
        MockMultipartFile file = new MockMultipartFile("file", "corrupt.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", new byte[]{0x50, 0x4B, 0x03, 0x04, 0x00});
        mockMvc.perform(multipart("/api/resume-imports/parse").file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void rejectsEmptyFile() throws Exception {
        ensureToken();
        MockMultipartFile file = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);
        mockMvc.perform(multipart("/api/resume-imports/parse").file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void rejectsOversizedFile() throws Exception {
        ensureToken();
        byte[] large = new byte[6 * 1024 * 1024]; // 6 MB > 5 MB limit
        MockMultipartFile file = new MockMultipartFile("file", "large.txt", "text/plain", large);
        mockMvc.perform(multipart("/api/resume-imports/parse").file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void rejectsUnsupportedFileType() throws Exception {
        ensureToken();
        MockMultipartFile file = new MockMultipartFile("file", "payload.exe", "application/octet-stream", new byte[]{1, 2, 3});
        mockMvc.perform(multipart("/api/resume-imports/parse").file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void rejectsExtensionAndMimeTypeMismatches() throws Exception {
        ensureToken();
        MockMultipartFile fakePdf = new MockMultipartFile("file", "resume.pdf", "text/plain",
                "not a pdf".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile fakeDocx = new MockMultipartFile("file", "resume.docx", "application/pdf",
                "not a docx".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile fakeTxt = new MockMultipartFile("file", "resume.txt", "application/pdf",
                "not text".getBytes(StandardCharsets.UTF_8));

        for (MockMultipartFile file : new MockMultipartFile[]{fakePdf, fakeDocx, fakeTxt}) {
            mockMvc.perform(multipart("/api/resume-imports/parse").file(file)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001));
        }
    }

    private void ensureToken() throws Exception {
        if (token != null) return;
        MvcResult result = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"import_user\",\"email\":\"import@example.com\",\"password\":\"correcthorse\"}"))
                .andExpect(status().isCreated()).andReturn();
        token = objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asText();
    }
}
