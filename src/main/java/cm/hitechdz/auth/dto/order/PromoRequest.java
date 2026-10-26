package cm.hitechdz.auth.dto.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record PromoRequest(
        @NotBlank(message = "Le code est obligatoire.")
        @Pattern(regexp = "^[A-Za-z0-9_-]{3,40}$", message = "3 à 40 caractères : lettres, chiffres, - ou _.")
        String code,

        @NotBlank(message = "Le type est obligatoire (PERCENT, FIXED ou FREE_SHIPPING).")
        String type,

        @PositiveOrZero(message = "La valeur doit être positive.")
        Long value,

        @Size(max = 200, message = "La description ne peut pas dépasser 200 caractères.")
        String description,

        Boolean active,
        Instant startsAt,
        Instant endsAt,

        @PositiveOrZero(message = "Le minimum d'achat doit être positif.")
        Long minSubtotal,

        @Positive(message = "Le plafond doit être positif.")
        Long maxDiscount,

        @Positive(message = "Le nombre d'utilisations doit être positif.")
        Integer maxUses,

        @Positive(message = "La limite par client doit être positive.")
        Integer maxUsesPerCustomer
) {}
