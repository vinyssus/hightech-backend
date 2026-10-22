package cm.hitechdz.auth.dto.catalog;

import java.time.Instant;
import java.util.UUID;

public record AdminReviewDto(
        Long id,
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
