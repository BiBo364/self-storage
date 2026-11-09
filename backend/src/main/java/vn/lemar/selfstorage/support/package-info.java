/**
 * Flow 7 - yêu cầu hỗ trợ và xử lý sự cố: khách gửi, FM phân công FS, FS xử lý (thay chìa, thu phí dịch vụ).
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Support (F7)",
        allowedDependencies = {"tenancy::application", "handover::application", "booking::application",
                "facility::application", "identity::application", "notification::application",
                "pricing::application", "audit::application", "media", "staffing::application"})
package vn.lemar.selfstorage.support;
