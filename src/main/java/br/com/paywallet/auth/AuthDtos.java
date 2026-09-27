package br.com.paywallet.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn,
                                String refreshToken, long refreshExpiresIn) {
    }

    public record EmailRequest(@NotBlank @Email String email) {
    }

    public record VerifyEmailRequest(@NotBlank @Email String email,
                                     @NotBlank @Pattern(regexp = "\\d{6}") String code) {
    }

    public record ResetPasswordRequest(@NotBlank @Email String email,
                                       @NotBlank @Pattern(regexp = "\\d{6}") String code,
                                       @NotBlank @Size(max = 72) String newPassword) {
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword,
                                        @NotBlank @Size(max = 72) String newPassword) {
    }
}
