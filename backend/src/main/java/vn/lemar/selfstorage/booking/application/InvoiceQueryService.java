package vn.lemar.selfstorage.booking.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.booking.application.exception.InvoiceNotFoundException;
import vn.lemar.selfstorage.booking.repository.BookingReadRepository;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;

/**
 * Khách đọc một hóa đơn của chính mình.
 *
 * <p>FE cần đường này để refetch sau khi cổng thanh toán redirect về: return URL chỉ nói giao dịch
 * thành công hay không, còn trạng thái hóa đơn thì do IPN chốt ở phía server. Không đọc lại thì FE
 * phải tự đoán, hoặc hiện trạng thái cũ.
 *
 * <p>Dùng chung projection với danh sách hóa đơn — cùng một hợp đồng, nên đọc qua cùng một read
 * model thay vì map entity thành dạng thứ hai.
 *
 * <p>Không kiểm xác minh email trong service: account chưa xác minh không được cấp
 * {@code ACCOUNT_ACTIVE} nên đã bị chặn ở tầng filter trước khi tới đây.
 */
@Service
public class InvoiceQueryService {

    private final BookingReadRepository readRepository;
    private final CurrentAccountProvider currentAccount;

    public InvoiceQueryService(BookingReadRepository readRepository, CurrentAccountProvider currentAccount) {
        this.readRepository = readRepository;
        this.currentAccount = currentAccount;
    }

    /**
     * Hóa đơn theo id, chỉ khi người gọi là chủ hóa đơn.
     *
     * @throws InvoiceNotFoundException hóa đơn không tồn tại <em>hoặc</em> thuộc người khác — hai ca
     *                                 trả cùng một lỗi để không tiết lộ id nào đang tồn tại
     */
    @Transactional(readOnly = true)
    public InvoiceListView findOwn(Long invoiceId) {
        return readRepository.findCustomerInvoice(currentAccount.getCurrentAccountId(), invoiceId)
                .orElseThrow(InvoiceNotFoundException::new);
    }
}
