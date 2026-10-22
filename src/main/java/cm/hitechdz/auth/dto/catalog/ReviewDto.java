package cm.hitechdz.auth.dto.catalog;

import java.time.Instant;

/** Avis public : prénom et ville de l'auteur uniquement. */
public record ReviewDto(
        Long id,
        int rating,
        String title,
        String body,
        boolean verified,
        int helpfulCount,
        Instant createdAt,
        String author,
        String city
) {}
