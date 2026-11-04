package vn.lemar.selfstorage.identity.application.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(

        @NotBlank
        @Email
        String email,

        @NotBlank
        @Size(min = 8)
        String password,

        @NotBlank
        @Size(max = 255)
        String name,

        @Size(max = 20)
        String phone
) {

    /** Tương thích test/call cũ chưa có hồ sơ. */
    public RegisterRequest(String email, String password) {
        this(email, password, null, null);
    }
}
