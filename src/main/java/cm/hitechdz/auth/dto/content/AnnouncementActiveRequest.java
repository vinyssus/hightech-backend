package cm.hitechdz.auth.dto.content;

import jakarta.validation.constraints.NotNull;

public record AnnouncementActiveRequest(
        @NotNull(message = "Indiquez si l'annonce est active")
        Boolean active
) {}
