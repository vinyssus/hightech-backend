package cm.hitechdz.auth.dto.order;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** Nouvel ordre d'affichage : les zones listées d'abord, dans cet ordre, puis les autres. */
public record ZoneReorderRequest(@NotEmpty(message = "Indiquez l'ordre des zones.") List<String> ids) {}
