package br.com.paywallet.user;

import java.time.Instant;

import br.com.paywallet.crypto.EncryptedString;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Customer profile. Money is not stored here but in the user's ledger account. */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Convert(converter = EncryptedString.class)
    @Column(nullable = false)
    private String document;

    @Column(name = "document_index", unique = true, length = 64)
    private String documentIndex;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role = Role.CUSTOMER;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    protected User() {
    }

    public User(String fullName, String document, String documentIndex, String email, String password,
                UserType type) {
        this.fullName = fullName;
        this.document = document;
        this.documentIndex = documentIndex;
        this.email = email;
        this.password = password;
        this.type = type;
    }

    public void verifyEmail(Instant now) {
        if (emailVerifiedAt == null) {
            emailVerifiedAt = now;
        }
    }

    public void changePassword(String passwordHash, Instant now) {
        this.password = passwordHash;
        this.passwordChangedAt = now;
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    public Long getId() { return id; }
    public String getFullName() { return fullName; }
    public String getDocument() { return document; }
    public String getEmail() { return email; }
    public String getPassword() { return password; }
    public UserType getType() { return type; }
    public Role getRole() { return role; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getEmailVerifiedAt() { return emailVerifiedAt; }
    public Instant getPasswordChangedAt() { return passwordChangedAt; }
}
