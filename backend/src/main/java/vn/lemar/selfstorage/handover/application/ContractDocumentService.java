package vn.lemar.selfstorage.handover.application;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;
import vn.lemar.selfstorage.handover.application.exception.HandoverConflictException;
import vn.lemar.selfstorage.handover.application.exception.HandoverNotFoundException;
import vn.lemar.selfstorage.handover.domain.RentalContract;
import vn.lemar.selfstorage.handover.repository.HandoverReadRepository;
import vn.lemar.selfstorage.handover.repository.HandoverReadRepository.ContractDocumentRow;
import vn.lemar.selfstorage.identity.application.Access;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;

/**
 * Spec 2.3: render tài liệu hợp đồng từ mẫu đang hiệu lực + dữ liệu phiên. Chưa có bảng mẫu (Flow 4) — dùng mẫu
 * dựng sẵn {@code builtin-v1}; tài liệu render động từ dữ liệu hiện tại nên {@code document_url} trỏ về endpoint này.
 * Còn đề nghị đổi mốc tính tiền {@code Pending} → không render (409).
 */
@Service
public class ContractDocumentService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final HandoverReadRepository readRepository;
    private final Access access;
    private final CurrentAccountProvider currentAccount;

    ContractDocumentService(HandoverReadRepository readRepository, Access access,
                            CurrentAccountProvider currentAccount) {
        this.readRepository = readRepository;
        this.access = access;
        this.currentAccount = currentAccount;
    }

    /** Khách của hợp đồng, hoặc FS/FM của cơ sở ({@code rental_order.read}). */
    @Transactional(readOnly = true)
    public String render(Long contractId) {
        ContractDocumentRow row = readRepository.findContractDocument(contractId)
                .orElseThrow(() -> new HandoverNotFoundException("Không tìm thấy hợp đồng"));
        if (!row.customerId().equals(currentAccount.getCurrentAccountId())) {
            access.can(HandoverQueryService.PERMISSION_READ, row.facilityId());
        }
        if (RentalContract.OVERRIDE_PENDING.equals(row.overrideStatus())) {
            throw new HandoverConflictException("Đề nghị đổi mốc tính tiền đang chờ FM duyệt — chưa thể in hợp đồng");
        }
        return html(row);
    }

    static String html(ContractDocumentRow row) {
        StringBuilder out = new StringBuilder(4096);
        out.append("<!doctype html><html lang=\"vi\"><head><meta charset=\"utf-8\"><title>Hợp đồng ")
                .append(esc(row.code())).append("</title><style>")
                .append("body{font-family:'Times New Roman',serif;max-width:780px;margin:32px auto;padding:0 16px;")
                .append("line-height:1.5;color:#111}h1{text-align:center;font-size:20px}")
                .append("h2{font-size:16px;margin-top:24px}table{border-collapse:collapse;width:100%}")
                .append("td{padding:4px 8px;vertical-align:top}td:first-child{width:38%;color:#444}")
                .append(".sign{display:flex;justify-content:space-between;margin-top:48px;text-align:center}")
                .append(".sign div{width:45%}.meta{font-size:12px;color:#666;text-align:center}")
                .append("@media print{.meta{display:none}}</style></head><body>")
                .append("<p class=\"meta\">Mã hợp đồng ").append(esc(row.code())).append(" · Mẫu ")
                .append(esc(row.termsVersion())).append(" · Trạng thái ").append(esc(row.status())).append("</p>")
                .append("<h1>HỢP ĐỒNG THUÊ KHOANG LƯU TRỮ</h1>")
                .append("<h2>Điều 1. Các bên</h2><table>")
                .append(tr("Bên cho thuê", "Lemar Self Storage — " + row.facilityName()))
                .append(tr("Địa chỉ cơ sở", row.facilityAddress()))
                .append(tr("Bên thuê", row.customerName()))
                .append(tr("Email", row.customerEmail()))
                .append(tr("Số điện thoại", row.customerPhone()))
                .append("</table><h2>Điều 2. Đối tượng thuê</h2><table>")
                .append(tr("Khoang chứa", row.unitCode() + " (" + row.unitType() + ")"))
                .append(tr("Diện tích", row.unitArea() == null ? null : row.unitArea().stripTrailingZeros()
                        .toPlainString() + " m²"))
                .append(tr("Hiện trạng khi bàn giao", row.inspectionNotes() == null ? "Theo biên bản bàn giao"
                        : row.inspectionNotes()))
                .append("</table><h2>Điều 3. Thời hạn và giá thuê</h2><table>")
                .append(tr("Ngày bắt đầu tính tiền", date(row.startDate())))
                .append(tr("Ngày kết thúc", date(row.endDate())))
                .append(tr("Thời hạn", row.period() + " tháng"))
                .append(tr("Giá thuê", money(row.monthlyPrice()) + " / tháng"))
                .append(tr("Tiền cọc đã đóng", money(row.depositAmount())))
                .append("</table><h2>Điều 4. Thanh toán</h2>")
                .append("<p>Bên thuê thanh toán tiền thuê tháng đầu trước khi nhận bàn giao khoang. Tiền cọc được ")
                .append("giữ riêng, không trừ vào tiền thuê và được xử lý khi trả kho theo chính sách của bên ")
                .append("cho thuê.</p>")
                .append("<h2>Điều 5. Quyền và nghĩa vụ</h2><ol>")
                .append("<li>Bên thuê không lưu trữ hàng cấm, chất dễ cháy nổ, thực phẩm tươi sống hoặc động vật.</li>")
                .append("<li>Bên thuê tự bảo quản chìa khóa/mã truy cập; mất chìa phải báo ngay cho cơ sở.</li>")
                .append("<li>Bên cho thuê bảo đảm an ninh khu vực, giữ nguyên hiện trạng khoang như biên bản.</li>")
                .append("<li>Gia hạn, trả kho trước hạn và phí phát sinh theo chính sách hiện hành của cơ sở.</li>")
                .append("</ol><div class=\"sign\"><div><b>BÊN CHO THUÊ</b><br>(ký, ghi rõ họ tên)</div>")
                .append("<div><b>BÊN THUÊ</b><br>(ký, ghi rõ họ tên)<br><br><br>").append(esc(row.customerName()))
                .append("</div></div></body></html>");
        return out.toString();
    }

    private static String tr(String label, String value) {
        return "<tr><td>" + esc(label) + "</td><td>" + esc(value == null ? "—" : value) + "</td></tr>";
    }

    private static String esc(String value) {
        // UTF-8: chỉ escape ký tự đặc biệt HTML, giữ nguyên tiếng Việt.
        return value == null ? "" : HtmlUtils.htmlEscape(value, "UTF-8");
    }

    private static String date(LocalDate date) {
        return date == null ? null : DATE.format(date);
    }

    private static String money(BigDecimal amount) {
        if (amount == null) {
            return "—";
        }
        return NumberFormat.getNumberInstance(Locale.forLanguageTag("vi-VN")).format(amount) + " đ";
    }
}
