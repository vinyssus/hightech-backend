package cm.hitechdz.auth.dto.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Carte produit (listes, recherche, favoris). */
public record ProductSummary(
        String id,
        String slug,
        String name,
        String brand,
        String category,
        long price,
        Long oldPrice,
        double rating,
        int reviews,
        String image,
        int stock,
        @JsonProperty("isNew") boolean isNew,
        int sold
) {}
