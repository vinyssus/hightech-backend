package cm.hitechdz.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ForgotPasswordRequest(
        @NotBlank(message = "L'adresse e-mail est obligatoire.")
        @Email(message = "Adresse e-mail invalide.")
        String email
) {}
