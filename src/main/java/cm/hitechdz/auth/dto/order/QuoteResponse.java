package cm.hitechdz.auth.dto.order;

import cm.hitechdz.auth.dto.catalog.CartLine;

import java.time.Instant;
import java.util.List;

public record QuoteResponse(
        String quoteId,
        Instant expiresAt,
        String currency,
        List<CartLine> items,
        long subtotal,
        long discount,
        long shipping,
        long total,
        PromoSummary promo,
        boolean canOrder,
        List<QuoteIssue> issues
) {}
