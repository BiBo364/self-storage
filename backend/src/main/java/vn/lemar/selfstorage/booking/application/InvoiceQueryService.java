package vn.lemar.selfstorage.booking.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.booking.application.exception.InvoiceNotFoundException;
import vn.lemar.selfstorage.booking.repository.InvoiceRepository;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;

/**
 * Khách đọc hóa đơn của chính mình.
 *
 * <p>FE cần đường này để refetch sau khi cổng thanh toán redirect về: return URL chỉ nói giao dịch
 * thành công hay không, còn trạng thái hóa đơn thì do IPN chốt ở phía server. Không đọc lại thì FE
 * phải tự đoán, hoặc hiện trạng thái cũ.
 *
 * <p>Không kiểm xác minh email như lúc mở phiên thanh toán: đây là phép đọc dữ liệu của chính
 * người gọi, và khách phải xác minh xong mới đi được tới bước có hóa đơn.
 */
@Service
public class InvoiceQueryService {

    private final InvoiceRepository invoices;
    private final CurrentAccountProvider currentAccount;

    public InvoiceQueryService(InvoiceRepository invoices, CurrentAccountProvider currentAccount) {
        this.invoices = invoices;
        this.currentAccount = currentAccount;
    }

    /**
     * Hóa đơn theo id, chỉ khi người gọi là chủ hóa đơn.
     *
     * @throws InvoiceNotFoundException hóa đơn không tồn tại <em>hoặc</em> thuộc người khác — hai ca
     *                                 trả cùng một lỗi để không tiết lộ id nào đang tồn tại
     */
    @Transactional(readOnly = true)
    public InvoiceView findOwn(Long invoiceId) {
        Long customerId = currentAccount.getCurrentAccountId();
        return invoices.findById(invoiceId)
                .filter(invoice -> customerId.equals(invoice.getCustomerId()))
                .map(InvoiceView::of)
                .orElseThrow(InvoiceNotFoundException::new);
    }
}
