package br.com.paywallet.auth;

import java.util.List;

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

    public record MfaChallengeResponse(boolean mfaRequired, String mfaToken, long expiresIn) {
    }

    /** Exactly one of the two is set. */
    public record LoginResult(TokenResponse tokens, MfaChallengeResponse challenge) {
    }

    /** {@code code}: from the authenticator app, or a recovery code. */
    public record MfaLoginRequest(@NotBlank String mfaToken, @NotBlank @Size(max = 20) String code) {
    }

    public record MfaSetupResponse(String secret, String otpauthUri) {
    }

    public record MfaCodeRequest(@NotBlank @Pattern(regexp = "\\d{6}") String code) {
    }

    public record DisableMfaRequest(@NotBlank String password, @NotBlank @Size(max = 20) String code) {
    }

    public record RecoveryCodesResponse(List<String> recoveryCodes) {
    }

    public record SetPinRequest(@NotBlank String password, @NotBlank @Pattern(regexp = "\\d{6}") String pin) {
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
