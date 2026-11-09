package vn.lemar.selfstorage.handover.application;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Bản PDF của hợp đồng, sinh từ đúng HTML mà {@link ContractDocumentService#render} trả (bản chốt lúc ký, hoặc bản
 * nháp render động) — cùng quyền, cùng 409. Không lưu file: PDF sinh lại được từ HTML bất cứ lúc nào.
 *
 * <p>Font nhúng là DejaVu Sans (có đủ dấu tiếng Việt; font chuẩn của PDF thì không). Bộ dựng PDF chỉ đọc XHTML và
 * CSS 2.1, nên HTML được chuẩn hoá (bỏ doctype HTML5, đóng thẻ rỗng) và thêm CSS riêng cho bản PDF.
 */
@Service
public class ContractPdfService {

    static final String FONT_FAMILY = "DejaVu Sans";
    private static final String PDF_CSS = "<style>body{font-family:'" + FONT_FAMILY + "',sans-serif;margin:0 auto}"
            + ".sign{display:block}.sign div{display:inline-block;width:45%;vertical-align:top}</style>";
    private static final Pattern DOCTYPE = Pattern.compile("(?i)<!doctype[^>]*>");
    private static final Pattern VOID_TAG = Pattern.compile("(?i)<(br|meta|hr|img|input|link)(\\s[^>]*?)?\\s*/?>");

    private final ContractDocumentService documents;

    ContractPdfService(ContractDocumentService documents) {
        this.documents = documents;
    }

    /** Khách của hợp đồng, hoặc FS/FM của cơ sở — kiểm quyền như bản HTML. */
    public byte[] render(Long contractId) {
        return toPdf(documents.render(contractId));
    }

    static byte[] toPdf(String html) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024)) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.useFont(() -> font("DejaVuSans.ttf"), FONT_FAMILY, 400, FontStyle.NORMAL, true);
            builder.useFont(() -> font("DejaVuSans-Bold.ttf"), FONT_FAMILY, 700, FontStyle.NORMAL, true);
            builder.withHtmlContent(toXhtml(html), null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Không tạo được PDF hợp đồng", e);
        }
    }

    /**
     * HTML của mẫu dựng sẵn → XHTML: bỏ doctype HTML5, đóng thẻ rỗng ({@code <br>}, {@code <meta>}), chèn CSS bản
     * PDF. Áp cả cho bản đã chốt lúc ký (lưu dạng HTML5) nên không sửa nội dung đã lưu.
     */
    static String toXhtml(String html) {
        String xhtml = DOCTYPE.matcher(html).replaceAll("");
        xhtml = VOID_TAG.matcher(xhtml).replaceAll(match -> {
            String attrs = match.group(2) == null ? "" : match.group(2);
            return java.util.regex.Matcher.quoteReplacement("<" + match.group(1) + attrs + "/>");
        });
        int head = xhtml.indexOf("</head>");
        return head < 0 ? PDF_CSS + xhtml : xhtml.substring(0, head) + PDF_CSS + xhtml.substring(head);
    }

    private static InputStream font(String file) {
        InputStream in = ContractPdfService.class.getResourceAsStream("/fonts/" + file);
        if (in == null) {
            throw new IllegalStateException("Thiếu font " + file + " trong classpath /fonts");
        }
        return in;
    }
}
