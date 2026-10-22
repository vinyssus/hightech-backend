package cm.hitechdz.auth.dto.catalog;

import java.util.List;

public record ReviewPage(
        List<ReviewDto> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        ReviewSummary summary
) {}
