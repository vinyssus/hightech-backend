package cm.hitechdz.auth.dto.order;

import java.time.Instant;

public record PromoDto(String code, String type, long value, String description, boolean active,
                       Instant startsAt, Instant endsAt, Long minSubtotal, Long maxDiscount,
                       Integer maxUses, Integer maxUsesPerCustomer, int usedCount,
                       Instant createdAt, Instant updatedAt) {}
