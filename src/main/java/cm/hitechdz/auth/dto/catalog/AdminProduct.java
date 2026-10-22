package cm.hitechdz.auth.dto.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/** Produit vu par l'admin : ProductSummary + images, imageDetails, description, specs, status, variantes. */
public record AdminProduct(
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
        List<ImageDetail> imageDetails,
        String description,
        List<SpecDto> specs,
        String status,
        List<AdminCombination> combinations,
        Instant createdAt,
        Instant updatedAt
) {}
