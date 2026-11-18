package cm.hitechdz.auth.dto.catalog;

import java.time.Instant;
import java.util.UUID;

/** id en chaîne (contrat du frontend d'administration). */
public record AdminReviewDto(
        String id,
        String productId,
        String productSlug,
        String productName,
        UUID userId,
        String author,
        String city,
        int rating,
        String title,
        String body,
        boolean verified,
        int helpfulCount,
        Instant createdAt
) {}
