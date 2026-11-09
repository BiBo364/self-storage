/**
 * Nhân sự (Flow 5): ca làm việc của FS, nghỉ phép, hồ sơ nhân viên; kiểm FS có ca / không nghỉ phép khi FM phân công
 * việc. Module nghiệp vụ gọi {@code staffing::application}; module này không phụ thuộc ngược booking / tenancy /
 * support — việc FS đang giữ đọc qua SPI {@code identity.application.StaffOpenWork}.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Staffing (F5 — nhân sự)",
        allowedDependencies = {"facility::application", "pricing::application", "identity::application",
                "notification::application", "audit::application"})
package vn.lemar.selfstorage.staffing;
