package cm.hitechdz.auth.dto.content;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** ids : toutes les affiches de l'emplacement, dans le nouvel ordre. */
public record BannerReorderRequest(
        @NotBlank(message = "L'emplacement est obligatoire.") String slot,
        @NotNull(message = "La liste des affiches est obligatoire.") List<Long> ids
) {}
