package br.com.paywallet.user;

import java.time.Instant;

import jakarta.persistence.Column;
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

    @Column(nullable = false, unique = true, length = 14)
    private String document;

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

    protected User() {
    }

    public User(String fullName, String document, String email, String password, UserType type) {
        this.fullName = fullName;
        this.document = document;
        this.email = email;
        this.password = password;
        this.type = type;
    }

    public Long getId() { return id; }
    public String getFullName() { return fullName; }
    public String getDocument() { return document; }
    public String getEmail() { return email; }
    public String getPassword() { return password; }
    public UserType getType() { return type; }
    public Role getRole() { return role; }
    public Instant getCreatedAt() { return createdAt; }
}
