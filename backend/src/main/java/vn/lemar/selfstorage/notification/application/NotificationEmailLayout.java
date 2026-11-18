package vn.lemar.selfstorage.notification.application;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

import org.springframework.web.util.HtmlUtils;

/**
 * Bọc title/body thành email HTML chuẩn: table layout + inline CSS (hiển thị tốt trên mọi mail
 * client), preheader cho preview, header thương hiệu, footer "email tự động".
 *
 * <p>Dùng cho {@code EmailSender.sendHtml(...)}; title/body được escape HTML trước khi nhúng.
 *
 * <p>Known limitation: một layout cố định dùng chung, chưa có hệ template theo {@code type}
 * của notification (spec sẽ cần sau). Chỉ hỗ trợ title + các đoạn văn, không có block tuỳ biến.
 */
public final class NotificationEmailLayout {

    private static final String BRAND = "KHO MỘC";
    private static final int PREHEADER_MAX = 120;
    private static final String PREHEADER_TOKEN = "{{preheader}}";
    private static final String PARAGRAPH_STYLE =
            "margin:0 0 12px;font-family:Arial,Helvetica,sans-serif;font-size:14px;line-height:22px;color:#374151;";

    private static final String HEADER = """
            <!DOCTYPE html>
            <html lang="vi">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Thông báo từ Kho Mộc</title>
            </head>
            <body style="margin:0;padding:0;background-color:#f3f4f6;">
              <div style="display:none;max-height:0;overflow:hidden;">{{preheader}}</div>
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0"
                     style="background-color:#f3f4f6;">
                <tr>
                  <td align="center" style="padding:24px 12px;">
                    <table role="presentation" width="600" cellpadding="0" cellspacing="0"
                           style="width:600px;max-width:100%;background-color:#ffffff;border-radius:8px;">
                      <tr>
                        <td style="background-color:#111827;padding:20px 32px;
                                   font-family:Arial,Helvetica,sans-serif;font-size:16px;font-weight:bold;
                                   color:#ffffff;letter-spacing:1px;">
                          KHO MỘC
                        </td>
                      </tr>
                      <tr>
                        <td style="padding:32px 32px 8px;font-family:Arial,Helvetica,sans-serif;">
                          <h1 style="margin:0 0 16px;font-size:20px;line-height:28px;color:#111827;">
            """;

    private static final String FOOTER = """
                        </td>
                      </tr>
                      <tr>
                        <td style="padding:20px 32px;background-color:#f9fafb;border-top:1px solid #e5e7eb;
                                   font-family:Arial,Helvetica,sans-serif;font-size:12px;line-height:18px;
                                   color:#6b7280;">
                          Email tự động từ Kho Mộc — vui lòng không trả lời.<br>
                          © 2026 Kho Mộc
                        </td>
                      </tr>
                    </table>
                  </td>
                </tr>
              </table>
            </body>
            </html>
            """;

    private NotificationEmailLayout() {
    }

    public static String wrap(String title, String body) {
        String safeTitle = escape(title == null ? "" : title.strip());
        return HEADER.replace(PREHEADER_TOKEN, escape(preheader(body)))
                + safeTitle
                + "</h1>"
                + paragraphs(body)
                + FOOTER;
    }

    private static String escape(String text) {
        return HtmlUtils.htmlEscape(text, StandardCharsets.UTF_8.name());
    }

    private static String preheader(String body) {
        String plain = body == null ? "" : body.replaceAll("\\s+", " ").strip();
        if (plain.length() <= PREHEADER_MAX) {
            return plain;
        }
        return plain.substring(0, PREHEADER_MAX) + "…";
    }

    private static String paragraphs(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        return Arrays.stream(body.strip().split("\\n\\s*\\n"))
                .map(part -> "\n                          <p style=\"" + PARAGRAPH_STYLE + "\">"
                        + escape(part).replace("\n", "<br>")
                        + "</p>")
                .collect(Collectors.joining());
    }
}
