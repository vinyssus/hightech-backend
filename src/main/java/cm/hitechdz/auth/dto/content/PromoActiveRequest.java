package cm.hitechdz.auth.dto.content;

import jakarta.validation.constraints.NotNull;

public record PromoActiveRequest(@NotNull(message = "Indiquez si la publicité est active.") Boolean active) {}
