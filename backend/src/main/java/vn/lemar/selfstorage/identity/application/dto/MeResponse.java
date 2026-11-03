package vn.lemar.selfstorage.identity.application.dto;

public record MeResponse(
        String email,
        String role,
        String name,
        String phone
) {

    /** Tương thích test cũ chưa có hồ sơ. */
    public MeResponse(String email, String role) {
        this(email, role, null, null);
    }
}
