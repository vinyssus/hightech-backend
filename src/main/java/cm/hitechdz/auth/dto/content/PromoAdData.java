package cm.hitechdz.auth.dto.content;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Partie « data » (JSON) des envois multipart du panneau publicitaire.
 * startsAt / endsAt : « 2026-12-01T08:00 » (heure de Douala) ou ISO 8601 avec fuseau (« 2026-12-01T07:00:00Z »).
 */
public record PromoAdData(
        @NotBlank(message = "Le texte alternatif est obligatoire.")
        @Size(min = 3, max = 120, message = "Le texte alternatif doit contenir entre 3 et 120 caractères.")
        String altText,
        String linkType,
        @Size(max = 500, message = "Le lien ne peut pas dépasser 500 caractères.")
        String linkValue,
        @PositiveOrZero(message = "La position doit être positive ou nulle.")
        Integer position,
        Boolean active,
        String startsAt,
        String endsAt,
        Boolean removeMobileImage
) {}
