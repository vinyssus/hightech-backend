package cm.hitechdz.auth.dto.order;

import cm.hitechdz.auth.dto.catalog.CartItemInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record QuoteRequest(
        @NotEmpty(message = "Le panier est vide.")
        @Size(max = 50, message = "50 articles maximum.")
        List<@Valid CartItemInput> items,

        @NotBlank(message = "Choisissez une zone de livraison.")
        String zoneId,

        @Size(max = 80, message = "La ville ne peut pas dépasser 80 caractères.")
        String city,

        @Size(max = 40, message = "Code promo invalide.")
        String promoCode
) {}
