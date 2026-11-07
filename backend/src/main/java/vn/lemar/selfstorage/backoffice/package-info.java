/**
 * Back-office (Flow 4/5 + quản trị hệ thống): báo cáo, hoá đơn / hợp đồng theo cơ sở, quản lý cơ sở / khoang / policy /
 * giá, tài khoản / phân quyền / nhật ký. Ghi qua lệnh của module sở hữu bảng.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Back-office (F4/F5/Admin)",
        allowedDependencies = {"facility::application", "pricing::application", "identity::application",
                "audit::application"})
package vn.lemar.selfstorage.backoffice;
