package cm.hitechdz.auth.dto.order;

public record OrderItemResponse(String productId, String sku, String name, String variantLabel, String image,
                                int qty, long unitPrice, long lineTotal) {}
