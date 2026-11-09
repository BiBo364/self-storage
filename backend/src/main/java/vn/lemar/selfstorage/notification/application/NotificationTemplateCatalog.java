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
                "Khoang {{unitCode}} — loại {{unitType}} — đã được chỉ định cho yêu cầu thuê của bạn. "
                        + "Xác nhận đề xuất và đặt cọc tại {{proposalLink}}. Lưu ý: khoang chưa được giữ trong lúc "
                        + "chờ — khoang chỉ được xác nhận cho khách hoàn tất thanh toán cọc đầu tiên (cọc trước giữ "
                        + "trước).");
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
        put(NotificationType.PROPOSAL_UNIT_TAKEN, NotificationRecipient.CUSTOMER,
                "Khoang đã có người đặt cọc trước",
                "Khoang {{unitCode}} đã có người đặt cọc trước. Cơ sở đang tìm khoang khác phù hợp cho bạn.");
        put(NotificationType.DEPOSIT_PAYMENT_SUCCEEDED, NotificationRecipient.CUSTOMER,
                "Đặt cọc thành công",
                "Hệ thống đã nhận tiền cọc cho đơn {{orderCode}}. Vui lòng chọn lịch hẹn check-in.");
        put(NotificationType.DEPOSIT_SUPPLEMENT_PAYMENT_SUCCEEDED, NotificationRecipient.CUSTOMER,
                "Đặt cọc bổ sung thành công",
                "Hệ thống đã nhận cọc bổ sung cho đơn {{orderCode}}. Khoang {{unitCode}} đã được giữ.");
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
        put(NotificationType.PAYMENT_DUPLICATE_REFUND_REQUIRED, NotificationRecipient.FM,
                "Có thanh toán trùng cần hoàn tiền",
                "Đơn {{orderCode}} có thêm giao dịch {{vnpTxnRef}} số tiền {{amount}} sau khi đã "
                        + "thanh toán. Cần hoàn tiền thủ công.");
        put(NotificationType.PAYMENT_PENDING_REVIEW_REQUIRED, NotificationRecipient.ADMIN,
                "Có giao dịch Pending cần đối soát",
                "Giao dịch {{vnpTxnRef}} của hoá đơn {{invoiceId}} số tiền {{amount}} đã Pending "
                        + "{{pendingAgeMinutes}} phút. Vui lòng tra portal VNPay và đối soát.");
        put(NotificationType.PAYMENT_REFUND_REQUIRED, NotificationRecipient.FM,
                "Có khoản cọc cần hoàn thủ công",
                "Đơn {{orderCode}} đã thu {{amount}} nhưng khoang không còn giữ được. "
                        + "Cần hoàn tiền thủ công.");
        put(NotificationType.PAYMENT_REFUND_PENDING, NotificationRecipient.CUSTOMER,
                "Khoản cọc sẽ được hoàn",
                "Đơn {{orderCode}} đã thanh toán nhưng khoang không còn giữ được. "
                        + "Bộ phận vận hành sẽ liên hệ hoàn tiền.");
        put(NotificationType.UNIT_CHANGE_PAYMENT_REQUIRED, NotificationRecipient.CUSTOMER,
                "Thanh toán để giữ khoang mới",
                "Đơn {{orderCode}}: cần thanh toán thêm {{amount}} để giữ khoang mới. "
                        + "Khoang chỉ được giữ sau khi thanh toán; xem hoá đơn trong mục Hoá đơn.");
        put(NotificationType.DEPOSIT_REFUND_PENDING, NotificationRecipient.CUSTOMER,
                "Cọc dư sẽ được hoàn",
                "Đơn {{orderCode}}: cọc dư {{refundAmount}} sẽ được cơ sở hoàn lại.");
        put(NotificationType.HANDOVER_KEY_REQUESTED, NotificationRecipient.FS,
                "Khách chờ nhận chìa khóa",
                "Khách của đơn {{orderCode}} đã xác nhận biên bản khoang {{unitCode}} và chọn nhận khóa cơ. "
                        + "Vui lòng giao chìa và xác nhận trên hệ thống.");
        put(NotificationType.FIRST_MONTH_REFUND_REQUIRED, NotificationRecipient.FM,
                "Cần hoàn tiền tháng đầu thủ công",
                "Đơn {{orderCode}} đã thanh toán tháng đầu nhưng bàn giao không hoàn tất ({{reason}}). "
                        + "Số tiền cần hoàn: {{refundAmount}}.");
        put(NotificationType.HANDOVER_COMPLETED, NotificationRecipient.CUSTOMER,
                "Bàn giao kho hoàn tất",
                "Đơn {{orderCode}} đã bàn giao khoang {{unitCode}}. Xem biên bản tại {{handoverLink}}, "
                        + "hợp đồng đã ký tại "
                        + "{{contractLink}}.{{accessNote}}");
        put(NotificationType.CONTRACT_EXPIRING_SOON, NotificationRecipient.CUSTOMER,
                "Hợp đồng thuê sắp hết hạn",
                "Hợp đồng {{contractCode}} (khoang {{unitCode}}) hết hạn ngày {{endDate}}, còn {{daysLeft}} ngày. "
                        + "Gia hạn hoặc gửi yêu cầu trả kho trong mục Kho của tôi.");
        put(NotificationType.CONTRACT_OVERDUE, NotificationRecipient.CUSTOMER,
                "Hợp đồng thuê đã quá hạn",
                "Hợp đồng {{contractCode}} (khoang {{unitCode}}) đã hết hạn ngày {{endDate}}. Phí quá hạn "
                        + "{{feePerDay}}đ/ngày được tính sau {{graceDays}} ngày ân hạn. "
                        + "Vui lòng gia hạn hoặc trả kho.");
        put(NotificationType.CONTRACT_OVERDUE, NotificationRecipient.FM,
                "Hợp đồng quá hạn",
                "Hợp đồng {{contractCode}} (khoang {{unitCode}}) đã quá hạn từ ngày {{endDate}}.");
        put(NotificationType.CONTRACT_EXTENDED, NotificationRecipient.CUSTOMER,
                "Gia hạn hợp đồng thành công",
                "Hợp đồng {{contractCode}} đã được gia hạn thêm {{months}} tháng, hết hạn mới ngày {{endDate}}.");
        put(NotificationType.RETURN_REQUESTED, NotificationRecipient.FM,
                "Yêu cầu trả kho mới",
                "Khách gửi yêu cầu trả khoang {{unitCode}} (hợp đồng {{contractCode}}) ngày mong muốn "
                        + "{{preferredDate}}. Vui lòng phân công FS.");
        put(NotificationType.RETURN_SCHEDULED, NotificationRecipient.CUSTOMER,
                "Lịch trả kho đã được xác nhận",
                "Lịch trả khoang {{unitCode}}: {{appointmentTime}}. Vui lòng dọn trống khoang trước buổi hẹn; khoang "
                        + "trống, không hư hỏng và không còn hoá đơn chưa trả thì được hoàn cọc.");
        put(NotificationType.RETURN_SCHEDULED, NotificationRecipient.FS,
                "Bạn được phân công buổi trả kho",
                "Buổi trả khoang {{unitCode}}: {{appointmentTime}}.");
        put(NotificationType.CHECKOUT_PAYMENT_REQUIRED, NotificationRecipient.CUSTOMER,
                "Cần thanh toán phần còn thiếu khi trả kho",
                "Tổng phí trả kho khoang {{unitCode}} là {{totalCharges}}đ, vượt tiền cọc {{depositAmount}}đ. Vui lòng "
                        + "thanh toán {{shortfall}}đ để hoàn tất trả kho.");
        put(NotificationType.CHECKOUT_REFUND_REQUIRED, NotificationRecipient.FM,
                "Cần hoàn cọc khi trả kho",
                "Khoang {{unitCode}} (hợp đồng {{contractCode}}) đã trả. "
                        + "Số tiền cần hoàn cho khách: {{refundAmount}}đ.");
        put(NotificationType.RENT_INVOICE_ISSUED, NotificationRecipient.CUSTOMER,
                "Hoá đơn tiền thuê tháng {{month}}",
                "Hợp đồng {{contractCode}} (khoang {{unitCode}}): tiền thuê kỳ {{periodFrom}} – {{periodTo}} là "
                        + "{{amount}}đ, hạn thanh toán {{dueDate}}. Thanh toán tại {{invoiceLink}}.");
        put(NotificationType.RENT_UNPAID, NotificationRecipient.FM,
                "Khách chưa trả tiền thuê",
                "Hoá đơn tiền thuê {{invoiceCode}} của hợp đồng {{contractCode}} (khoang {{unitCode}}) đã quá hạn "
                        + "chưa thanh toán ({{amount}}đ). Cần liên hệ khách.");
        put(NotificationType.CONTRACT_ACCESS_SUSPENDED, NotificationRecipient.CUSTOMER,
                "Quyền truy cập khoang tạm khoá",
                "Hợp đồng {{contractCode}} (khoang {{unitCode}}) đã quá hạn {{days}} ngày. Mã khoá số đã tạm khoá; "
                        + "gia hạn hoặc trả kho và thanh toán phí quá hạn để mở lại.");
        put(NotificationType.CONTRACT_ACCESS_SUSPENDED, NotificationRecipient.FM,
                "Hợp đồng quá hạn kéo dài",
                "Hợp đồng {{contractCode}} (khoang {{unitCode}}) đã quá hạn {{days}} ngày, mã khoá số đã tạm khoá. "
                        + "Liên hệ khách để gia hạn hoặc trả kho; khách không phản hồi thì xử lý thu hồi theo "
                        + "quy trình của cơ sở.");
        put(NotificationType.CONTRACT_ACCESS_RESTORED, NotificationRecipient.CUSTOMER,
                "Quyền truy cập khoang đã mở lại",
                "Hợp đồng {{contractCode}} (khoang {{unitCode}}) {{restoreNote}} Lấy mã khoá số mới trong trang kho "
                        + "đang thuê.");
        put(NotificationType.RETURN_NO_SHOW, NotificationRecipient.CUSTOMER,
                "Bạn đã lỡ buổi trả kho",
                "Bạn không đến buổi trả kho {{appointmentTime}} (khoang {{unitCode}}, hợp đồng {{contractCode}}). "
                        + "{{customerNote}}");
        put(NotificationType.RETURN_NO_SHOW, NotificationRecipient.FM,
                "Khách không đến buổi trả kho",
                "Khách không đến buổi trả kho {{appointmentTime}} (khoang {{unitCode}}, hợp đồng {{contractCode}}). "
                        + "{{fmNote}}");
        put(NotificationType.RETURN_DATE_SHIFTED, NotificationRecipient.CUSTOMER,
                "Ngày trả kho được dời",
                "Ngày bạn chọn đã kín lịch — buổi trả khoang {{unitCode}} được dời sang {{appointmentTime}}.");
        put(NotificationType.CHECKOUT_SHORTFALL_UNPAID, NotificationRecipient.FM,
                "Khách chưa trả phần thiếu khi trả kho",
                "Khoang {{unitCode}} (hợp đồng {{contractCode}}) đã đóng trả kho nhưng hoá đơn phần còn thiếu "
                        + "{{shortfall}}đ quá hạn chưa thanh toán. Cần thu thủ công.");
        put(NotificationType.SUPPORT_REQUEST_CREATED, NotificationRecipient.FM,
                "Yêu cầu hỗ trợ mới",
                "Khách gửi yêu cầu hỗ trợ #{{requestId}} ({{category}}): {{title}}. Vui lòng phân công FS.");
        put(NotificationType.SUPPORT_ASSIGNED, NotificationRecipient.FS,
                "Bạn được giao xử lý yêu cầu hỗ trợ",
                "Yêu cầu hỗ trợ #{{requestId}} ({{category}}): {{title}}.");
        put(NotificationType.SUPPORT_ASSIGNED, NotificationRecipient.CUSTOMER,
                "Yêu cầu hỗ trợ đang được xử lý",
                "Yêu cầu hỗ trợ #{{requestId}} đã được giao cho nhân viên {{staffName}}.");
        put(NotificationType.SUPPORT_RESOLVED, NotificationRecipient.CUSTOMER,
                "Yêu cầu hỗ trợ đã được xử lý",
                "Yêu cầu hỗ trợ #{{requestId}} đã xử lý: {{resolution}}{{feeNote}}");
        put(NotificationType.CHECKOUT_COMPLETED, NotificationRecipient.CUSTOMER,
                "Trả kho hoàn tất",
                "Hợp đồng {{contractCode}} đã kết thúc. Tiền cọc hoàn lại: {{refundAmount}}đ (cơ sở xử lý thủ công).");
        put(NotificationType.STAFF_SHIFT_CHANGED, NotificationRecipient.FS,
                "Ca làm việc thay đổi",
                "Ca làm việc tại {{facilityCode}}: {{change}}.");
        put(NotificationType.STAFF_LEAVE_REQUESTED, NotificationRecipient.FM,
                "FS xin nghỉ phép",
                "{{staffName}} xin nghỉ từ {{fromDate}} đến {{toDate}}. Lý do: {{reason}}.");
        put(NotificationType.STAFF_LEAVE_REVIEWED, NotificationRecipient.FS,
                "Đơn nghỉ phép {{result}}",
                "Đơn nghỉ phép từ {{fromDate}} đến {{toDate}} {{result}}.{{noteText}}");
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
