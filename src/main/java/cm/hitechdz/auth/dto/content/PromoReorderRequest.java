package cm.hitechdz.auth.dto.content;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/** ids : toutes les publicités, dans le nouvel ordre. */
public record PromoReorderRequest(@NotNull(message = "La liste des publicités est obligatoire.") List<UUID> ids) {}
