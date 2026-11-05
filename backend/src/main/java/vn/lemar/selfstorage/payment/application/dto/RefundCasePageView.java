package vn.lemar.selfstorage.payment.application.dto;

import java.util.List;

public record RefundCasePageView(List<RefundCaseView> items, int page, int size,
                                 long totalElements, int totalPages) {
}
