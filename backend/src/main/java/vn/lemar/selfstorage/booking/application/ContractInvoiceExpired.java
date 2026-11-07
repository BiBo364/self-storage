package vn.lemar.selfstorage.booking.application;

/**
 * Hoá đơn gắn hợp đồng (vd tháng đầu) quá {@code due_date} và vừa chuyển {@code Expired}. Phát đồng bộ trong
 * transaction của cron — module sở hữu hợp đồng xử lý nhánh "quá hạn thanh toán tháng đầu" (Scheduled Jobs).
 */
public record ContractInvoiceExpired(Long invoiceId, Long contractId, String invoiceType) {
}
