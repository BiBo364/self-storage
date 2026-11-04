package vn.lemar.selfstorage.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String email; // normalized: lowercase + trim trước khi lưu/so sánh

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(length = 255)
    private String name;

    @Column(length = 20)
    private String phone;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Setter
    private AccountStatus status = AccountStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Account(String email, String passwordHash, Role role) {
        this(email, passwordHash, null, null, role);
    }

    public Account(String email, String passwordHash, String name, String phone, Role role) {
        this.email = normalize(email);
        this.passwordHash = passwordHash;
        this.name = name;
        this.phone = phone;
        this.role = role;
    }

    public static String normalize(String rawEmail) {
        return rawEmail == null ? null : rawEmail.trim().toLowerCase();
    }

    public boolean isVerified() {
        return emailVerifiedAt != null;
    }

    /** Đăng nhập / cấp authority chỉ khi account `ACTIVE` và email đã xác minh (Business workflow). */
    public boolean isLoginAllowed() {
        return status == AccountStatus.ACTIVE && isVerified();
    }

    public void markEmailVerified() {
        this.emailVerifiedAt = Instant.now();
    }
}
