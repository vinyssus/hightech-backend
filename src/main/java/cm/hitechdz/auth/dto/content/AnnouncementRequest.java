package cm.hitechdz.auth.dto.content;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Création / modification complète. active absent = true à la création, inchangé en modification ;
 * position absente = à la fin à la création, inchangée en modification. Dates ISO 8601 (UTC).
 */
public record AnnouncementRequest(
        @NotBlank(message = "Le message est obligatoire")
        @Size(min = 3, max = 160, message = "Le message doit contenir entre 3 et 160 caractères")
        String message,
        @Size(max = 500, message = "Le lien ne peut pas dépasser 500 caractères")
        String link,
        Boolean active,
        @PositiveOrZero(message = "La position doit être positive ou nulle")
        Integer position,
        Instant startsAt,
        Instant endsAt
) {}
