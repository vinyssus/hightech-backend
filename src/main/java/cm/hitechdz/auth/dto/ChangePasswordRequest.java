package cm.hitechdz.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import static cm.hitechdz.auth.dto.ValidationRules.*;

public record ChangePasswordRequest(
        @NotBlank(message = "Le mot de passe actuel est obligatoire.")
        String currentPassword,

        @NotBlank(message = "Le nouveau mot de passe est obligatoire.")
        @Pattern(regexp = PASSWORD_REGEX, message = PASSWORD_MESSAGE)
        @Size(max = PASSWORD_MAX, message = "Le mot de passe ne peut pas dépasser 72 caractères.")
        String newPassword
) {}
