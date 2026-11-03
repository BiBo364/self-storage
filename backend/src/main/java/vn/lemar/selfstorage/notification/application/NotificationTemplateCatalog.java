package vn.lemar.selfstorage.notification.application;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Template notification hardcode theo type (MVP — spec `db-table-draft.md › Notification`).
 *
 * <p>Template dùng placeholder {@code {{key}}}; caller phải truyền đủ giá trị, thiếu là từ chối.
 * Mỗi template khai đúng cặp (type, recipient) theo cột Recipient của catalog; truyền sai
 * recipient là lỗi lập trình — không có fallback.
 */
@Component
public class NotificationTemplateCatalog {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)\\}\\}");

    /** Template cho một cặp (type, recipient). */
    public record Template(String title, String body) {

        public String renderTitle(Map<String, ?> values) {
            return render(title, values);
        }

        public String renderBody(Map<String, ?> values) {
            return render(body, values);
        }

        private static String render(String template, Map<String, ?> values) {
            Matcher matcher = PLACEHOLDER.matcher(template);
            StringBuilder result = new StringBuilder();
            while (matcher.find()) {
                String key = matcher.group(1);
                Object value = values == null ? null : values.get(key);
                if (value == null) {
                    throw new IllegalArgumentException("Thiếu giá trị cho placeholder {{" + key + "}}");
                }
                matcher.appendReplacement(result, Matcher.quoteReplacement(String.valueOf(value)));
            }
            matcher.appendTail(result);
            return result.toString();
        }
    }

    private record Key(NotificationType type, NotificationRecipient recipient) {
    }

    private final Map<Key, Template> templates = new HashMap<>();

    public NotificationTemplateCatalog() {
        put(NotificationType.RENTAL_REQUEST_APPROVED, NotificationRecipient.CUSTOMER,
                "Yêu cầu thuê kho đã được duyệt",
                "Khoang {{unitCode}} đã được chỉ định cho yêu cầu thuê của bạn. "
                        + "Xác nhận đề xuất tại {{proposalLink}}.");
        put(NotificationType.RENTAL_REQUEST_REJECTED, NotificationRecipient.CUSTOMER,
                "Yêu cầu thuê kho bị từ chối",
                "Yêu cầu thuê kho của bạn đã bị từ chối. Lý do: {{reason}}.");
        put(NotificationType.RENTAL_REQUEST_REJECTED_BANNED, NotificationRecipient.CUSTOMER,
                "Yêu cầu thuê kho bị từ chối",
                "Tài khoản của bạn hiện đang bị chặn, nên yêu cầu đặt kho của bạn đã bị từ chối. "
                        + "Vui lòng liên hệ CSKH nếu có sai sót.");
        put(NotificationType.PROPOSAL_REJECTED, NotificationRecipient.FM,
                "Khách hàng đã từ chối đề xuất",
                "Khách đã từ chối khoang {{unitCode}}, lý do: {{reason}}.");
        put(NotificationType.PROPOSAL_REPROPOSAL_REQUIRED, NotificationRecipient.FM,
                "Cần đề xuất khoang khác",
                "Đơn {{orderCode}}: khoang {{unitCode}} không còn khả dụng, cần đề xuất khoang khác.");
        put(NotificationType.PROPOSAL_REPROPOSED, NotificationRecipient.CUSTOMER,
                "Có đề xuất kho mới",
                "Cơ sở đã gửi đề xuất khoang mới. Xác nhận tại {{confirmLink}}.");
        put(NotificationType.DEPOSIT_PAYMENT_SUCCEEDED, NotificationRecipient.CUSTOMER,
                "Đặt cọc thành công",
                "Hệ thống đã nhận tiền cọc cho đơn {{orderCode}}. Vui lòng chọn lịch hẹn check-in.");
        put(NotificationType.APPOINTMENT_CREATED, NotificationRecipient.CUSTOMER,
                "Lịch hẹn check-in đã được tạo",
                "Lịch hẹn của bạn: {{appointmentTime}} tại {{facilityAddress}}. Vui lòng mang theo CCCD/Passport.");
        put(NotificationType.APPOINTMENT_CREATED, NotificationRecipient.FM,
                "Có lịch check-in mới cần phân công",
                "Đơn {{orderCode}}: lịch hẹn {{appointmentTime}} tại {{facilityAddress}} cần phân công FS.");
        put(NotificationType.APPOINTMENT_CANCELED_NO_SHOW, NotificationRecipient.CUSTOMER,
                "Lịch hẹn check-in đã bị hủy do bạn không đến",
                "Lịch hẹn cũ: {{appointmentTime}}. Bạn có thể đặt lịch mới tại {{rescheduleLink}}.");
        put(NotificationType.FS_ASSIGNED, NotificationRecipient.FS,
                "Bạn được phân công lịch check-in",
                "Đơn {{orderCode}}: lịch hẹn {{appointmentTime}} tại {{facilityAddress}}, khoang {{unitCode}}.");
        put(NotificationType.FS_ASSIGNMENT_REQUIRED, NotificationRecipient.FM,
                "Lịch check-in chưa có nhân viên phụ trách",
                "Đơn {{orderCode}}: lịch hẹn {{appointmentTime}} tại {{facilityAddress}} chưa có FS phụ trách.");
        put(NotificationType.HANDOVER_REJECTED, NotificationRecipient.FM,
                "Khách từ chối khoang tại check-in",
                "Khoang {{unitCode}} bị khách từ chối tại check-in. Lý do: {{reason}}.");
        put(NotificationType.HANDOVER_OVERDUE, NotificationRecipient.CUSTOMER,
                "Chưa hoàn tất bàn giao trong hạn",
                "Phiên bàn giao chưa hoàn tất trong hạn. Đặt lịch mới tại {{rescheduleLink}}.");
        put(NotificationType.RENTAL_ORDER_EXPIRING_SOON, NotificationRecipient.CUSTOMER,
                "Đơn thuê kho sắp hết hạn",
                "Đơn {{orderCode}} sẽ hết hạn lúc {{expiresAt}}, còn {{daysLeft}} ngày.");
        put(NotificationType.RENTAL_ORDER_CANCELED, NotificationRecipient.CUSTOMER,
                "Đơn thuê kho {{outcome}}",
                "Đơn {{orderCode}} {{outcome}}. Lý do: {{reason}}.");
        put(NotificationType.RENTAL_ORDER_CANCELED, NotificationRecipient.FM,
                "Đơn thuê kho {{outcome}}",
                "Đơn {{orderCode}} {{outcome}}. Lý do: {{reason}}.");
        put(NotificationType.DEPOSIT_REFUND_REQUIRED, NotificationRecipient.FM,
                "Cần hoàn cọc thủ công",
                "Đơn {{orderCode}} đổi sang khoang có cọc thấp hơn. Số tiền cần hoàn: {{refundAmount}}.");
        put(NotificationType.UNIT_CHANGE_PAYMENT_REQUIRED, NotificationRecipient.CUSTOMER,
                "Thanh toán để giữ khoang mới",
                "Đơn {{orderCode}}: cần thanh toán thêm {{amount}} để giữ khoang mới. "
                        + "Khoang chỉ được giữ sau khi thanh toán; xem hoá đơn trong mục Hoá đơn.");
        put(NotificationType.DEPOSIT_REFUND_PENDING, NotificationRecipient.CUSTOMER,
                "Cọc dư sẽ được hoàn",
                "Đơn {{orderCode}}: cọc dư {{refundAmount}} sẽ được cơ sở hoàn lại.");
        put(NotificationType.HANDOVER_COMPLETED, NotificationRecipient.CUSTOMER,
                "Bàn giao kho hoàn tất",
                "Đơn {{orderCode}} đã bàn giao khoang {{unitCode}}. Xem biên bản tại {{handoverLink}}.");
    }

    /**
     * Trả template đúng cặp (type, recipient); không fallback.
     *
     * @throws IllegalArgumentException nếu type/recipient không có template (ví dụ {@code OTHER},
     *         hoặc caller truyền recipient không thuộc catalog của type)
     */
    public Template resolve(NotificationType type, NotificationRecipient recipient) {
        if (type == NotificationType.OTHER) {
            throw new IllegalArgumentException("OTHER không dùng template, phải tạo thủ công");
        }
        Template template = templates.get(new Key(type, recipient));
        if (template == null) {
            throw new IllegalArgumentException("Không có template cho " + type + "/" + recipient);
        }
        return template;
    }

    private void put(NotificationType type, NotificationRecipient recipient, String title, String body) {
        templates.put(new Key(type, recipient), new Template(title, body));
    }
}
