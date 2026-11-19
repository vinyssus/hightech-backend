package cm.hitechdz.auth.dto.content;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Réglages modifiables du logo incrusté. position : SOUTH_EAST, SOUTH_WEST, SOUTH, NORTH_EAST, NORTH_WEST ou CENTER. */
public record WatermarkSettingsDto(
        @NotNull(message = "Indiquez si le logo est activé.") Boolean enabled,
        @NotBlank(message = "La position est obligatoire.") String position,
        @NotNull(message = "La taille est obligatoire.")
        @Min(value = 10, message = "La taille du logo doit être comprise entre 10 et 60 % de la largeur.")
        @Max(value = 60, message = "La taille du logo doit être comprise entre 10 et 60 % de la largeur.")
        Integer widthPercent,
        @NotNull(message = "La marge est obligatoire.")
        @Min(value = 0, message = "La marge doit être comprise entre 0 et 10 %.")
        @Max(value = 10, message = "La marge doit être comprise entre 0 et 10 %.")
        Integer marginPercent,
        @NotNull(message = "L'opacité est obligatoire.")
        @Min(value = 20, message = "L'opacité doit être comprise entre 20 et 100 %.")
        @Max(value = 100, message = "L'opacité doit être comprise entre 20 et 100 %.")
        Integer opacity
) {}
