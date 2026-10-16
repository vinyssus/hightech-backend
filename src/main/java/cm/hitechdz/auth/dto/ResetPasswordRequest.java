package cm.hitechdz.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import static cm.hitechdz.auth.dto.ValidationRules.*;

public record ResetPasswordRequest(
        @NotBlank(message = "L'adresse e-mail est obligatoire.")
        @Email(message = "Adresse e-mail invalide.")
        String email,

        @NotBlank(message = "Le code est obligatoire.")
        @Pattern(regexp = "^\\d{6}$", message = "Le code doit contenir 6 chiffres.")
        String code,

        @NotBlank(message = "Le mot de passe est obligatoire.")
        @Pattern(regexp = PASSWORD_REGEX, message = PASSWORD_MESSAGE)
        @Size(max = PASSWORD_MAX, message = "Le mot de passe ne peut pas dépasser 72 caractères.")
        String password
) {}
