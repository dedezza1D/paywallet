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
            @NotBlank String fullName,
            @NotBlank
            @Pattern(regexp = "\\d{11}|\\d{14}", message = "must be a CPF (11 digits) or CNPJ (14 digits), digits only")
            String document,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, message = "must have at least 8 characters") String password,
            @NotNull UserType type) {
    }

    public record UserResponse(Long id, String fullName, String document, String email,
                               UserType type, Instant createdAt) {

        public static UserResponse from(User u) {
            return new UserResponse(u.getId(), u.getFullName(), u.getDocument(), u.getEmail(),
                    u.getType(), u.getCreatedAt());
        }
    }
}
