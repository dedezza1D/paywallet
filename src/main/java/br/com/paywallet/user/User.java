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

    @Column(name = "transaction_pin_hash", length = 100)
    private String transactionPinHash;

    @Column(name = "pin_changed_at")
    private Instant pinChangedAt;

    @Convert(converter = EncryptedString.class)
    @Column(name = "totp_secret")
    private String totpSecret;

    @Column(name = "totp_enabled_at")
    private Instant totpEnabledAt;

    @Column(name = "terms_version", length = 20)
    private String termsVersion;

    @Column(name = "terms_accepted_at")
    private Instant termsAcceptedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

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

    public void changeTransactionPin(String pinHash, Instant now) {
        this.transactionPinHash = pinHash;
        this.pinChangedAt = now;
    }

    /** A new secret replaces any previous one and stays inactive until a code from it is confirmed. */
    public void startTotpEnrollment(String secret) {
        this.totpSecret = secret;
        this.totpEnabledAt = null;
    }

    public void enableTotp(Instant now) {
        this.totpEnabledAt = now;
    }

    public void disableTotp() {
        this.totpSecret = null;
        this.totpEnabledAt = null;
    }

    public void acceptTerms(String version, Instant now) {
        this.termsVersion = version;
        this.termsAcceptedAt = now;
    }

    public void close(Instant now) {
        this.closedAt = now;
    }

    public boolean isClosed() {
        return closedAt != null;
    }

    public boolean isTotpEnabled() {
        return totpEnabledAt != null;
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
    public String getTransactionPinHash() { return transactionPinHash; }
    public String getTotpSecret() { return totpSecret; }
    public String getTermsVersion() { return termsVersion; }
    public Instant getTermsAcceptedAt() { return termsAcceptedAt; }
}
