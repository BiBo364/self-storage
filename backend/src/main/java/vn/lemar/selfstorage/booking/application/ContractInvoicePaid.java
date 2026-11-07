package vn.lemar.selfstorage.booking.application;

import java.time.Instant;

/**
 * Hoá đơn gắn hợp đồng (tháng đầu, gia hạn, phí…) vừa chuyển {@code Paid}. Booking không gọi sang module sở hữu hợp
 * đồng (handover/tenancy) — phát event đồng bộ trong transaction chốt thanh toán; listener lỗi thì rollback cả bước
 * chốt (giao dịch cổng vẫn giữ {@code Success}).
 *
 * @param invoiceType giá trị DB của {@code Invoice.type} ({@code Rental}, {@code Extension}, …)
 */
public record ContractInvoicePaid(Long invoiceId, Long contractId, String invoiceType, Instant paidAt) {
}
