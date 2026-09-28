package br.com.paywallet.user;

import java.time.Instant;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class UserDtos {

    private UserDtos() {
    }

    public record CreateUserRequest(
            @NotBlank @Size(max = 255) String fullName,
            @NotBlank
            @Pattern(regexp = "\\d{11}|\\d{14}", message = "must be a CPF (11 digits) or CNPJ (14 digits), digits only")
            String document,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 12, max = 72, message = "must have between 12 and 72 characters") String password,
            @NotNull UserType type) {
    }

    public record UserResponse(Long id, String fullName, String document, String email, boolean emailVerified,
                               UserType type, boolean transactionPinSet, boolean twoFactorEnabled,
                               Instant createdAt) {

        public static UserResponse from(User u) {
            return new UserResponse(u.getId(), u.getFullName(), u.getDocument(), u.getEmail(), u.isEmailVerified(),
                    u.getType(), u.getTransactionPinHash() != null, u.isTotpEnabled(), u.getCreatedAt());
        }
    }
}
