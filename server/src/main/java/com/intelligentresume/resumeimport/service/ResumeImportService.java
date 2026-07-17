package com.intelligentresume.resumeimport.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.resumeimport.dto.ResumeImportResponse;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ResumeImportService {
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    public ResumeImportResponse parse(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw new BusinessException(ErrorCode.VALIDATION);
        }
        String name = file.getOriginalFilename() == null ? "resume" : file.getOriginalFilename();
        String lowerName = name.toLowerCase(Locale.ROOT);
        try {
            ParsedText parsed = extract(file.getBytes(), lowerName);
            String text = parsed.text().replace("\u0000", "").trim();
            if (text.isBlank()) throw new BusinessException(ErrorCode.VALIDATION);
            Map<String, Object> normalized = Map.of(
                    "basics", Map.of("summary", text, "_source", "uploaded:" + name, "_pending", false),
                    "work", List.of(), "education", List.of(), "skills", List.of(), "projects", List.of());
            return new ResumeImportResponse(name, parsed.contentType(), text, normalized, false);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.VALIDATION);
        }
    }

    private ParsedText extract(byte[] bytes, String lowerName) throws Exception {
        if (lowerName.endsWith(".pdf")) {
            try (PDDocument document = PDDocument.load(bytes)) {
                return new ParsedText("application/pdf", new PDFTextStripper().getText(document));
            }
        }
        if (lowerName.endsWith(".docx")) {
            try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes));
                 XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
                return new ParsedText("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        extractor.getText());
            }
        }
        if (lowerName.endsWith(".txt")) {
            return new ParsedText("text/plain", new String(bytes, StandardCharsets.UTF_8));
        }
        throw new BusinessException(ErrorCode.VALIDATION);
    }

    private record ParsedText(String contentType, String text) {}
}
