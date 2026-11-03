package vn.lemar.selfstorage.booking.application;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.booking.repository.BookingReadRepository;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;

/**
 * Danh sách hoá đơn của chính khách đang đăng nhập — ownership, không dùng RBAC permission.
 * Mới nhất trước; MVP chưa phân trang.
 */
@Service
public class InvoiceListQueryService {

    private final BookingReadRepository readRepository;
    private final CurrentAccountProvider currentAccount;

    public InvoiceListQueryService(BookingReadRepository readRepository, CurrentAccountProvider currentAccount) {
        this.readRepository = readRepository;
        this.currentAccount = currentAccount;
    }

    @Transactional(readOnly = true)
    public List<InvoiceListView> listOwn() {
        return readRepository.findCustomerInvoices(currentAccount.getCurrentAccountId());
    }
}
