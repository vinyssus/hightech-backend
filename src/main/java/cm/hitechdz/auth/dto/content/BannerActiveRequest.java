package cm.hitechdz.auth.dto.content;

import jakarta.validation.constraints.NotNull;

public record BannerActiveRequest(@NotNull(message = "Indiquez si l'affiche est active.") Boolean active) {}
