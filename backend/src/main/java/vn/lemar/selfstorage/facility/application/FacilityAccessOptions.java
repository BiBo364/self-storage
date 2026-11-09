package vn.lemar.selfstorage.facility.application;

/**
 * Loại quyền truy cập khoang cơ sở đang bật (Flow 2.4): khóa cơ ({@code enabled_key_access}) và mã số
 * ({@code enabled_code_access}).
 */
public record FacilityAccessOptions(boolean keyAccess, boolean codeAccess) {
}
