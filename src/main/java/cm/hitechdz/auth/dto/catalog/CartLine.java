package cm.hitechdz.auth.dto.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Ligne de panier recalculée par le serveur (le prix du serveur fait foi). */
public record CartLine(
        String productId,
        String sku,
        String name,
        String image,
        long unitPrice,
        Long oldPrice,
        int stock,
        int qty,
        boolean available,
        @JsonInclude(JsonInclude.Include.NON_NULL) String message
) {}
