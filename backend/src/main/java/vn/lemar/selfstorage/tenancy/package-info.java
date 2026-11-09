/**
 * Flow 3 - quản lý kho đang thuê: xem kho, gia hạn, yêu cầu trả kho (FM phân công FS).
 *
 * <p>Chiều phụ thuộc: tenancy -> handover, booking. Checkout/overdue đọc tenancy qua named interface.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Tenancy (F3)",
        allowedDependencies = {"handover::application", "booking::application", "facility::application",
                "pricing::application", "identity::application", "payment::application",
                "notification::application", "audit::application", "staffing::application"})
package vn.lemar.selfstorage.tenancy;
