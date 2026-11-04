package vn.lemar.selfstorage.payment.application.dto;

import java.util.List;

public record PendingPaymentCasePageView(List<PendingPaymentCaseView> items, int page, int size,
                                         long totalElements, int totalPages) {
}
