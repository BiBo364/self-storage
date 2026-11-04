package vn.lemar.selfstorage.booking.application;

import java.util.List;

/** Một trang hoá đơn FM — metadata đủ để FE vẽ pagination. */
public record FmInvoicePageView(List<FmInvoiceListView> items, int page, int size,
                                long totalElements, int totalPages) {
}
