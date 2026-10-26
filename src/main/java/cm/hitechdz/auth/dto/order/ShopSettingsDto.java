package cm.hitechdz.auth.dto.order;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

/** freeShippingFrom null = pas de livraison offerte automatique. */
public record ShopSettingsDto(
        @PositiveOrZero(message = "Le seuil doit être positif.")
        Long freeShippingFrom,

        @Min(value = 1, message = "Entre 1 et 120 minutes.") @Max(value = 120, message = "Entre 1 et 120 minutes.")
        Integer quoteValidityMinutes,

        @Min(value = 5, message = "Entre 5 et 1440 minutes.") @Max(value = 1440, message = "Entre 5 et 1440 minutes.")
        Integer reservationMinutes,

        @Min(value = 1, message = "Entre 1 et 30 minutes.") @Max(value = 30, message = "Entre 1 et 30 minutes.")
        Integer paymentTimeoutMinutes
) {}
