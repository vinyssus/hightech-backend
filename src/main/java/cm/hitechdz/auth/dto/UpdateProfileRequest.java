package cm.hitechdz.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import static cm.hitechdz.auth.dto.ValidationRules.*;

public record UpdateProfileRequest(
        @NotBlank(message = "Le prénom est obligatoire.")
        @Size(min = 2, max = 40, message = "Le prénom doit contenir entre 2 et 40 caractères.")
        String firstName,

        @NotBlank(message = "Le nom est obligatoire.")
        @Size(min = 2, max = 40, message = "Le nom doit contenir entre 2 et 40 caractères.")
        String lastName,

        @NotBlank(message = "L'adresse e-mail est obligatoire.")
        @Email(message = "Adresse e-mail invalide.")
        @Size(max = 254, message = "L'adresse e-mail est trop longue.")
        String email,

        @NotBlank(message = "Le numéro de téléphone est obligatoire.")
        @Pattern(regexp = PHONE_REGEX, message = PHONE_MESSAGE)
        String phone
) {}
