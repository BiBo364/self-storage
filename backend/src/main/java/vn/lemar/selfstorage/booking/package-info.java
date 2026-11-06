/**
 * Flow 1 - đặt kho: yêu cầu thuê, đề xuất khoang, đặt cọc và chọn lịch check-in.
 *
 * <p>Đầu chuỗi phụ thuộc: không được gọi sang handover, checkout hay tenancy.
 * Muốn báo cho các module sau thì dùng domain event (issue #15 mục 1).
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Booking (F1)",
        allowedDependencies = {"facility::application", "pricing::application",
                "identity::application", "payment", "payment::application", "payment::events",
                "payment::dto", "notification", "notification::application", "audit::application"})
package vn.lemar.selfstorage.booking;
