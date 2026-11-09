package vn.lemar.selfstorage.handover.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import vn.lemar.selfstorage.handover.repository.HandoverReadRepository.ContractDocumentRow;

/** PDF sinh từ đúng mẫu HTML của hợp đồng — giữ được dấu tiếng Việt nhờ font nhúng. */
class ContractPdfServiceTest {

    @Test
    void rendersTheBuiltinTemplateWithVietnameseText() throws Exception {
        String html = ContractDocumentService.html(row());

        byte[] pdf = ContractPdfService.toPdf(html);
        java.nio.file.Files.write(java.nio.file.Path.of("target", "contract-sample.pdf"), pdf);

        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("HỢP ĐỒNG THUÊ KHOANG LƯU TRỮ")
                    .contains("Nguyễn Thị Ánh")
                    .contains("Bên thuê")
                    .contains("500.000 đ / tháng");
        }
    }

    @Test
    void normalizesHtml5MarkupToXhtml() {
        String xhtml = ContractPdfService.toXhtml("<!doctype html><html><head><meta charset=\"utf-8\"></head>"
                + "<body>a<br>b<BR/>c<hr></body></html>");

        assertThat(xhtml).doesNotContainIgnoringCase("<!doctype")
                .contains("<meta charset=\"utf-8\"/>")
                .contains("a<br/>b<BR/>c<hr/>")
                .contains(ContractPdfService.FONT_FAMILY);
    }

    @Test
    void addsPdfStylesEvenWithoutHead() {
        assertThat(ContractPdfService.toXhtml("<html><body>x</body></html>")).startsWith("<style>");
    }

    private static ContractDocumentRow row() {
        return new ContractDocumentRow(11L, "HD-Q7-261109-TEST", "Signed", "builtin-v1", new BigDecimal("500000"),
                new BigDecimal("1000000"), 3, LocalDate.of(2026, 11, 9), LocalDate.of(2027, 2, 8), null,
                Instant.now(), 32L, 6L, "Nguyễn Thị Ánh", "anh@example.com", "0901234567", "A-01", "Small",
                new BigDecimal("2.00"), 1L, "Self Storage Quận 7", "123 Nguyễn Văn Linh, Quận 7", 5L,
                "Khoang sạch, khoá tốt", null);
    }
}
