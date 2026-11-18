package cm.hitechdz.auth.dto.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Commande saisie par un administrateur après une discussion WhatsApp.
 * Prix, frais et total sont toujours recalculés par le serveur (aucun montant n'est accepté ici).
 */
public record AdminCreateOrderRequest(
        String source,
        @NotEmpty(message = "Ajoutez au moins un article.")
        @Size(max = 20, message = "20 articles maximum par commande.")
        List<@Valid OrderItemInput> items,
        @NotNull(message = "Les coordonnées de livraison sont obligatoires.")
        @Valid DeliveryInput delivery,
        String paymentMethod
) {}
