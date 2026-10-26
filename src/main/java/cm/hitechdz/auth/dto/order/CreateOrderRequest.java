package cm.hitechdz.auth.dto.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Aucun prix, frais, total ni statut n'est lu ici : tout est recalculé par le serveur. */
public record CreateOrderRequest(
        @NotBlank(message = "Le devis est obligatoire : demandez d'abord un devis.")
        String quoteId,

        @NotEmpty(message = "Le panier est vide.")
        @Size(max = 50, message = "50 articles maximum.")
        List<@Valid OrderItemInput> items,

        @Size(max = 40, message = "Code promo invalide.")
        String promoCode,

        @NotNull(message = "Les coordonnées de livraison sont obligatoires.")
        @Valid
        DeliveryInput delivery,

        @NotBlank(message = "Choisissez un mode de paiement.")
        String paymentMethod
) {}
