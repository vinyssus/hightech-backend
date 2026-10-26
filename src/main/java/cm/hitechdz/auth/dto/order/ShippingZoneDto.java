package cm.hitechdz.auth.dto.order;

import java.time.Instant;

public record ShippingZoneDto(String id, String label, long fee, String delay, boolean requiresCity,
                              boolean pickup, boolean codAllowed, boolean active, int position, Instant updatedAt) {}
