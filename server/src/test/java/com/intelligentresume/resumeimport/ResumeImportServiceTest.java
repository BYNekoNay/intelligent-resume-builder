package com.intelligentresume.resumeimport;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.resumeimport.service.ResumeImportService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.io.ByteArrayOutputStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResumeImportServiceTest {
    private final ResumeImportService service = new ResumeImportService();

    @Test void parsesTxtWithoutStoringOriginal(){var response=service.parse(new MockMultipartFile("file","resume.txt","text/plain","Java developer".getBytes()));assertThat(response.extractedText()).isEqualTo("Java developer");assertThat(response.originalFileStored()).isFalse();}
    @Test void parsesDocx() throws Exception {byte[] bytes;try(XWPFDocument doc=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){doc.createParagraph().createRun().setText("Spring Boot engineer");doc.write(out);bytes=out.toByteArray();}assertThat(service.parse(new MockMultipartFile("file","resume.docx",null,bytes)).extractedText()).contains("Spring Boot");}
    @Test void parsesPdf() throws Exception {byte[] bytes;try(PDDocument doc=new PDDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){PDPage page=new PDPage();doc.addPage(page);try(PDPageContentStream stream=new PDPageContentStream(doc,page)){stream.beginText();stream.setFont(PDType1Font.HELVETICA,12);stream.newLineAtOffset(50,700);stream.showText("Backend engineer");stream.endText();}doc.save(out);bytes=out.toByteArray();}assertThat(service.parse(new MockMultipartFile("file","resume.pdf",null,bytes)).extractedText()).contains("Backend engineer");}
    @Test void rejectsUnknownType(){assertThatThrownBy(()->service.parse(new MockMultipartFile("file","resume.exe",null,new byte[]{1}))).isInstanceOf(BusinessException.class);}
}
