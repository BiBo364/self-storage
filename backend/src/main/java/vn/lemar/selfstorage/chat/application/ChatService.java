package vn.lemar.selfstorage.chat.application;

import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vn.lemar.selfstorage.chat.application.ChatModelClient.Reply;
import vn.lemar.selfstorage.chat.application.ChatModelClient.Turn;
import vn.lemar.selfstorage.facility.application.FacilityCatalogQuery;
import vn.lemar.selfstorage.facility.application.FacilityCatalogView;
import vn.lemar.selfstorage.pricing.application.PricingAdminCommands;
import vn.lemar.selfstorage.pricing.application.PricingAdminCommands.PolicyRow;
import vn.lemar.selfstorage.pricing.application.PricingAdminCommands.UnitTypeRow;

/**
 * Chatbot tư vấn: ghép system prompt từ catalog công khai (cơ sở, loại khoang, giá, chính sách) và gọi mô hình.
 * Giới hạn: tối đa {@link #MAX_TURNS} lượt, mỗi lượt {@link #MAX_CHARS} ký tự; mỗi IP {@link #RATE_LIMIT} lần /
 * {@link #RATE_WINDOW}. Không đọc dữ liệu cá nhân — câu hỏi về đơn / hoá đơn cụ thể được hướng về trang tài khoản.
 */
@Service
public class ChatService {

    static final int MAX_TURNS = 20;
    static final int MAX_CHARS = 2000;
    static final int RATE_LIMIT = 30;
    static final Duration RATE_WINDOW = Duration.ofMinutes(10);
    static final String REFUSAL_TEXT = "Xin lỗi, mình không hỗ trợ được yêu cầu này. Bạn có thể hỏi về cơ sở, loại "
            + "khoang, giá thuê hoặc quy trình thuê kho nhé.";
    private static final List<String> PUBLIC_POLICIES = List.of("invoice.deposit_months", "invoice.deposit_due_days",
            "order.expiry_days", "appointment.booking_window_days", "contract.prepaid_months",
            "extension.max_months", "overdue.grace_days", "overdue.fee_per_day", "fee.cleaning", "fee.lost_key",
            "proposal.max_rejection_count", "handover.max_rejection_count");
    private static final Logger LOG = LoggerFactory.getLogger(ChatService.class);

    private final ChatModelClient model;
    private final FacilityCatalogQuery facilities;
    private final PricingAdminCommands pricing;
    private final Map<String, Deque<Instant>> calls = new ConcurrentHashMap<>();

    ChatService(ChatModelClient model, FacilityCatalogQuery facilities, PricingAdminCommands pricing) {
        this.model = model;
        this.facilities = facilities;
        this.pricing = pricing;
    }

    /** Lỗi đầu vào / giới hạn / chưa cấu hình — controller map sang HTTP. */
    public static class ChatException extends RuntimeException {

        private final int status;

        ChatException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    public Reply reply(List<Turn> turns, String clientKey) {
        if (turns == null || turns.isEmpty() || turns.size() > MAX_TURNS) {
            throw new ChatException(400, "Hội thoại phải có từ 1 đến " + MAX_TURNS + " tin nhắn");
        }
        for (Turn turn : turns) {
            if (turn.content() == null || turn.content().isBlank() || turn.content().length() > MAX_CHARS) {
                throw new ChatException(400, "Mỗi tin nhắn tối đa " + MAX_CHARS + " ký tự");
            }
            if (!"user".equals(turn.role()) && !"assistant".equals(turn.role())) {
                throw new ChatException(400, "Vai trò tin nhắn không hợp lệ");
            }
        }
        if (!"user".equals(turns.get(turns.size() - 1).role())) {
            throw new ChatException(400, "Tin nhắn cuối phải là của khách");
        }
        if (!model.available()) {
            throw new ChatException(503, "Trợ lý tư vấn chưa được cấu hình (thiếu GEMINI_API_KEY)");
        }
        throttle(clientKey);
        try {
            Reply reply = model.complete(systemPrompt(), turns);
            if (reply.refused() || reply.text().isBlank()) {
                return new Reply(REFUSAL_TEXT, reply.refused());
            }
            return reply;
        } catch (ChatException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.warn("Gọi trợ lý tư vấn lỗi: {}", e.getMessage());
            throw new ChatException(502, "Trợ lý đang bận, vui lòng thử lại sau ít phút");
        }
    }

    /** System prompt dựng lại mỗi lượt, thứ tự cố định để câu trả lời ổn định. */
    String systemPrompt() {
        NumberFormat money = NumberFormat.getNumberInstance(Locale.forLanguageTag("vi-VN"));
        List<UnitTypeRow> types = pricing.unitTypes();
        Map<Long, String> typeNames = types.stream().collect(Collectors.toMap(UnitTypeRow::id, UnitTypeRow::name));
        StringBuilder out = new StringBuilder("""
                Bạn là trợ lý tư vấn của Lemar Self Storage — chuỗi kho tự quản cho thuê khoang chứa đồ.
                Trả lời bằng tiếng Việt, ngắn gọn, thân thiện. Chỉ dùng thông tin bên dưới; không bịa giá, địa chỉ hay
                chính sách. Câu hỏi về đơn / hoá đơn / hợp đồng cụ thể của khách: hướng khách đăng nhập và xem mục
                "Kho của tôi" hoặc "Hoá đơn", hoặc gửi yêu cầu ở mục "Hỗ trợ". Không thu thập mật khẩu hay thông tin
                thanh toán. Viết văn bản thuần (khung chat không hiển thị markdown: không dùng **, #, bảng); liệt kê
                thì dùng "-" hoặc "1." đầu dòng. Không nhắc tên key chính sách nội bộ (vd invoice.deposit_months) —
                diễn đạt bằng lời.

                Quy trình thuê kho: (1) gửi yêu cầu đặt kho trên website (chọn cơ sở, loại khoang, ngày bắt đầu, số
                tháng); (2) cơ sở duyệt và đề xuất khoang, khách đồng ý hoặc từ chối; (3) đặt cọc qua payOS (chuyển
                khoản); (4) đặt lịch check-in; (5) tại cơ sở: xác minh danh tính, kiểm tra khoang, ký hợp đồng, thanh
                toán tháng đầu, nhận chìa khoá hoặc mã khoá số. Đang thuê có thể gia hạn, gửi yêu cầu trả kho hoặc báo
                sự cố. Trả kho: kiểm tra khoang, đối trừ phí (vệ sinh, hư hỏng, mất chìa, hoá đơn còn nợ) vào tiền cọc,
                phần dư được hoàn.

                ## Cơ sở
                """);
        for (FacilityCatalogView f : facilities.listActive()) {
            out.append("- ").append(f.name()).append(" (mã ").append(f.code()).append(")");
            if (f.address() != null) {
                out.append(", địa chỉ: ").append(f.address());
            }
            if (f.operatingHours() != null) {
                out.append(", giờ mở cửa: ").append(f.operatingHours());
            }
            out.append(", loại khoang: ").append(f.unitTypeIds().stream().map(typeNames::get)
                    .filter(java.util.Objects::nonNull).collect(Collectors.joining(", "))).append('\n');
        }
        out.append("\n## Loại khoang và giá thuê tham khảo\n");
        for (UnitTypeRow t : types) {
            out.append("- ").append(t.name()).append(": ").append(t.width()).append(" × ").append(t.depth())
                    .append(" × ").append(t.height()).append(" m (").append(t.area()).append(" m²), ")
                    .append(money.format(t.monthlyPrice())).append(" đ/tháng");
            if (t.description() != null) {
                out.append(" — ").append(t.description());
            }
            out.append('\n');
        }
        out.append("\n## Chính sách (key = giá trị)\n");
        for (PolicyRow p : pricing.policies()) {
            if (PUBLIC_POLICIES.contains(p.key())) {
                out.append("- ").append(p.key()).append(" = ").append(p.value());
                if (p.description() != null) {
                    out.append(" (").append(p.description()).append(")");
                }
                out.append('\n');
            }
        }
        return out.toString();
    }

    private void throttle(String clientKey) {
        Instant now = Instant.now();
        Deque<Instant> window = calls.computeIfAbsent(clientKey == null ? "anon" : clientKey,
                k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst().isBefore(now.minus(RATE_WINDOW))) {
                window.pollFirst();
            }
            if (window.size() >= RATE_LIMIT) {
                throw new ChatException(429, "Bạn hỏi hơi nhanh — vui lòng thử lại sau ít phút");
            }
            window.addLast(now);
        }
    }
}
