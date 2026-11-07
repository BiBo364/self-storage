/**
 * Trả kho rút gọn (bản nháp Flow 2.a) — biên bản trả kho, quyết toán tiền cọc, bảo trì rồi mở lại khoang.
 *
 * <p>Chiều phụ thuộc: checkout -> tenancy, handover, booking. Không module nào phụ thuộc ngược checkout.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Checkout (F2.a)",
        allowedDependencies = {"tenancy::application", "handover::application", "booking::application",
                "facility::application", "pricing::application", "identity::application",
                "notification::application", "audit::application"})
package vn.lemar.selfstorage.checkout;
