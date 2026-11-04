package vn.lemar.selfstorage.payment.application;

import java.util.Collection;
import java.util.Optional;
import vn.lemar.selfstorage.payment.application.dto.SuccessfulPayment;

/**
 * Cho module nghiệp vụ hỏi "hóa đơn này có phiên thanh toán đang mở không" mà không phải chạm
 * vào bảng của payment.
 *
 * <p>Spec Flow 1.4 chặn đặt cọc khi khoang "đang có giao dịch khác xử lý (chưa bị timeout)".
 * Khóa ở đây suy ra từ dữ liệu sẵn có chứ không phải khóa phân tán: một giao dịch còn
 * {@code Pending} và tạo chưa quá {@link PaymentSession#WINDOW} nghĩa là phiên còn mở. Cách này
 * tự hết hạn, sống sót qua restart và không cần bảng mới.
 */
public interface PaymentSessionQuery {

    /** Có ít nhất một phiên còn mở trong nhóm hóa đơn này không. Danh sách rỗng trả {@code false}. */
    boolean hasOpenSession(Collection<Long> invoiceIds);

    /**
     * Đã có giao dịch {@code PAY/Success} cho hoá đơn chưa. IPN ghi giao dịch trước khi listener
     * booking chạy, nên invoice có thể còn {@code Unpaid} trong lúc tiền đã vào — chặn mở phiên
     * mới để khách không trả lần hai.
     */
    boolean hasSuccessfulPayment(Long invoiceId);

    /** Cùng invoice này có phiên {@code Pending} còn hạn không — để trả lại URL thay vì mở phiên mới. */
    boolean hasOpenSessionForInvoice(Long invoiceId);

    /** URL còn hạn của phiên cũ; rỗng khi cache hết hạn hoặc Redis lỗi. */
    Optional<String> openPaymentUrl(Long invoiceId);

    /** Giao dịch PAY/Success của invoice — dùng cho job sửa state booking khi listener IPN lỗi. */
    Optional<SuccessfulPayment> findSuccessfulPayment(Long invoiceId);
}
