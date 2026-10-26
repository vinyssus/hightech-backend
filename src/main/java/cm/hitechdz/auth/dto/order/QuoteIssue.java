package cm.hitechdz.auth.dto.order;

/** code : PRICE_CHANGED, OUT_OF_STOCK, QUANTITY_EXCEEDED ou INVALID_VARIANT. */
public record QuoteIssue(String productId, String sku, String code, String message) {}
