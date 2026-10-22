package cm.hitechdz.auth.dto.catalog;

import java.util.List;

/** Page du catalogue public : format de pagination habituel + facettes. */
public record CatalogPage(
        List<ProductSummary> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        Facets facets
) {}
