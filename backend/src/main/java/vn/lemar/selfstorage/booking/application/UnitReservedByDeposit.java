package vn.lemar.selfstorage.booking.application;

/**
 * Khoang vừa chuyển {@code Reserved} vì một đơn đã trả cọc. Nội bộ booking — {@link CompetingInvoiceCanceller} nghe
 * sau commit để chặn ngay các hoá đơn cọc khác đang trỏ khoang này.
 */
record UnitReservedByDeposit(Long unitId, Long orderId) {
}
