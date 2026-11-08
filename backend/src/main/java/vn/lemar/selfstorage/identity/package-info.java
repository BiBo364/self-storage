/**
 * Tài khoản, xác thực và phân quyền RBAC.
 *
 * <p>Module nền, không phụ thuộc ngược lại module nghiệp vụ nào.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Identity", allowedDependencies = {"facility::application", "audit::application"})
package vn.lemar.selfstorage.identity;
