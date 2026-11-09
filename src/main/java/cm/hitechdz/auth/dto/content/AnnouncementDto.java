package cm.hitechdz.auth.dto.content;

import java.time.Instant;

public record AnnouncementDto(Long id, String message, String link, boolean active, int position,
                              Instant startsAt, Instant endsAt, Instant createdAt, Instant updatedAt) {}
