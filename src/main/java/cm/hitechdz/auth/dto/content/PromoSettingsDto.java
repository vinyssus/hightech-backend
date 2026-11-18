package cm.hitechdz.auth.dto.content;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Réglages du panneau. dismissMode : SESSION, DAY ou NEVER_AGAIN. */
public record PromoSettingsDto(
        @NotNull(message = "Indiquez si le panneau est activé.") Boolean enabled,
        @NotNull(message = "Le délai d'apparition est obligatoire.")
        @Min(value = 500, message = "Le délai d'apparition doit être compris entre 500 et 10 000 ms.")
        @Max(value = 10000, message = "Le délai d'apparition doit être compris entre 500 et 10 000 ms.")
        Integer delayMs,
        @NotNull(message = "La durée d'affichage est obligatoire.")
        @Min(value = 2000, message = "La durée de chaque publicité doit être comprise entre 2 000 et 15 000 ms.")
        @Max(value = 15000, message = "La durée de chaque publicité doit être comprise entre 2 000 et 15 000 ms.")
        Integer intervalMs,
        @NotNull(message = "Indiquez si le panneau s'affiche sur mobile.") Boolean showOnMobile,
        @NotBlank(message = "Le mode de fermeture est obligatoire (SESSION, DAY ou NEVER_AGAIN).") String dismissMode
) {}
