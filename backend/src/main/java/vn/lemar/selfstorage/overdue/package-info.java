/**
 * Flow 6 - xử lý quá hạn hợp đồng: nhắc sắp hết hạn, phí quá hạn cộng dồn theo ngày, danh sách quá hạn cho FM.
 * Gia hạn do tenancy xử lý.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Overdue (F6)",
        allowedDependencies = {"tenancy::application", "handover::application", "booking::application",
                "pricing::application", "identity::application", "notification::application",
                "audit::application"})
package vn.lemar.selfstorage.overdue;
