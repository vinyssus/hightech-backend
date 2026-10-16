package cm.hitechdz.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Utilisé par /refresh et /logout. */
public record RefreshTokenRequest(
        @NotBlank(message = "Le refresh token est obligatoire.")
        String refreshToken
) {}
