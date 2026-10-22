package cm.hitechdz.auth.dto.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Fiche produit publique : ProductSummary + images, description, caractéristiques et variantes. */
public record ProductDetail(
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
        int sold,
        List<String> images,
        String description,
        List<SpecDto> specs,
        List<VariantGroup> variants,
        List<Combination> combinations
) {}
