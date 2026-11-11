package cm.hitechdz.auth.dto.assistant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Un message de la conversation (l'historique est conservé par le navigateur). */
public record ChatMessage(
        @NotBlank(message = "Le rôle est obligatoire.")
        @Pattern(regexp = "user|assistant", message = "Rôle invalide (user ou assistant).")
        String role,
        @NotBlank(message = "Le message est vide.")
        @Size(max = 1000, message = "Le message ne peut pas dépasser 1000 caractères.")
        String content
) {}
